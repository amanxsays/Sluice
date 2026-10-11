package dev.sluice.api;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;
import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import dev.sluice.core.BackoffCalculator;
import dev.sluice.core.Job;
import dev.sluice.core.JobHandler;
import dev.sluice.core.JobsRepository;
import dev.sluice.core.SimulatedApiCallHandler;
import dev.sluice.core.TokenBucket;
import dev.sluice.core.Worker;
import io.micrometer.core.instrument.MeterRegistry;

/** Local learning lab. All claims, heartbeats, retries and acknowledgements use the real queue. */
@Service
@ConditionalOnProperty(name = "sluice.lab.enabled", havingValue = "true", matchIfMissing = true)
public class TestLabService {
    private static final Set<String> SCENARIOS = Set.of(
            "batch", "idempotency", "locking", "heartbeat", "lease-expiry", "retry", "rate-limit");
    private final JobsRepository repository;
    private final DataSource dataSource;
    private final MeterRegistry metrics;
    private final String upstreamUrl;
    private final ExecutorService executor = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "sluice-test-lab");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, Run> runs = new ConcurrentHashMap<>();
    private Run active;

    public record Options(String scenario, int jobCount, int workerCount, int latencyMs,
                          int claimDelayMs, int leaseSeconds, int maxAttempts, int retryAfterSeconds) {}
    public record Event(long sequence, Instant at, String kind, Long jobId, String workerId,
                        String detail, Instant leaseExpiresAt) {}
    public record Check(String label, boolean passed, String detail) {}
    public record Snapshot(String id, Options options, String status, Instant startedAt,
                           Instant finishedAt, List<Job> jobs, List<Event> events,
                           List<Long> returnedIds, List<Check> checks, String error,
                           int peakClaimed, int heartbeatCount, long lockedJobId) {}

    public TestLabService(JobsRepository repository, DataSource dataSource, MeterRegistry metrics,
                          @Value("${sluice.mock-upstream.url}") String upstreamUrl) {
        this.repository = repository;
        this.dataSource = dataSource;
        this.metrics = metrics;
        this.upstreamUrl = upstreamUrl;
    }

    public synchronized Snapshot start(Options options) {
        validate(options);
        if (active != null && active.status.equals("running")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A test is already running. Wait for its result.");
        }
        Run run = new Run(options);
        // Retain the latest 20 reports. Jobs remain in PostgreSQL and the dashboard.
        if (runs.size() >= 20) runs.values().stream()
                .filter(previous -> !previous.status.equals("running"))
                .min(java.util.Comparator.comparing(previous -> previous.startedAt))
                .ifPresent(previous -> runs.remove(previous.id));
        runs.put(run.id, run);
        active = run;
        executor.submit(() -> execute(run));
        return snapshot(run);
    }

    public Snapshot get(String id) {
        Run run = runs.get(id);
        if (run == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Test report not found; the API may have restarted.");
        return snapshot(run);
    }

    public synchronized Snapshot current() {
        return active == null ? null : snapshot(active);
    }

    private Snapshot snapshot(Run run) {
        return new Snapshot(run.id, run.options, run.status, run.startedAt, run.finishedAt,
                repository.findByJobType(run.type), List.copyOf(run.events),
                List.copyOf(run.returnedIds), run.checks, run.error,
                run.peakClaimed.get(), run.heartbeats.get(), run.lockedJobId);
    }

    private static void validate(Options o) {
        if (o == null || !SCENARIOS.contains(o.scenario())) bad("Choose a supported test.");
        if (o.jobCount() < 1 || o.jobCount() > 40) bad("Choose 1–40 jobs or submissions.");
        if (o.workerCount() < 1 || o.workerCount() > 8) bad("Choose 1–8 worker threads.");
        if (o.latencyMs() < 1000 || o.latencyMs() > 10000) bad("Latency must be 1,000–10,000 ms.");
        if (o.claimDelayMs() < 250 || o.claimDelayMs() > 2000) bad("Claim pause must be 250–2,000 ms.");
        if (o.leaseSeconds() < 1 || o.leaseSeconds() > 10) bad("Lease must be 1–10 seconds.");
        if (o.maxAttempts() < 2 || o.maxAttempts() > 5) bad("Failure budget must be 2–5 attempts.");
        if (o.retryAfterSeconds() < 1 || o.retryAfterSeconds() > 5) bad("Retry delay must be 1–5 seconds.");
        if (o.scenario().equals("batch") && o.jobCount() <= o.workerCount()) bad("Choose more jobs than workers for this test.");
        if (o.scenario().equals("locking") && (o.workerCount() < 2 || o.jobCount() <= o.workerCount()))
            bad("Locking needs at least two workers and more jobs than workers.");
        if (o.scenario().equals("idempotency") && o.jobCount() < 2) bad("Idempotency needs at least two submissions.");
        if (Set.of("heartbeat", "lease-expiry", "retry").contains(o.scenario())
                && (o.jobCount() != 1 || o.workerCount() != 1)) bad("This test uses one job and one recovery/processing worker.");
        if (o.scenario().equals("heartbeat") && o.latencyMs() <= o.leaseSeconds() * 1000 + 1000)
            bad("Heartbeat latency must exceed the initial lease by more than one second.");
        if ((long) o.jobCount() * (o.latencyMs() + o.claimDelayMs()) / o.workerCount() > 140000)
            bad("Choose fewer jobs, more workers, or lower latency to keep the test under three minutes.");
    }

    private static void bad(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }

    private void execute(Run run) {
        List<Future<?>> workers = new ArrayList<>();
        Connection lock = null;
        try {
            Options o = run.options;
            String payload = "{\"latencyMs\":" + o.latencyMs() + ",\"shouldFail\":"
                    + o.scenario().equals("retry") + ",\"retryAfterSeconds\":" + o.retryAfterSeconds() + "}";
            if (o.scenario().equals("idempotency")) {
                // Submit concurrently against the actual unique index, rather than reusing a frontend result.
                List<Future<?>> submissions = new ArrayList<>();
                for (int i = 0; i < o.jobCount(); i++) submissions.add(executor.submit(() -> {
                    Job job = repository.enqueue(run.type, payload, "lab-key:" + run.id, 0);
                    run.returnedIds.add(job.id());
                    run.event("submitted", job.id(), null, "Same idempotency key returned job #" + job.id(), null);
                }));
                for (Future<?> submission : submissions) submission.get();
            } else {
                for (int i = 0; i < o.jobCount(); i++) {
                    Job job = repository.enqueue(run.type, payload, null, 0);
                    run.returnedIds.add(job.id());
                    run.event("enqueued", job.id(), null, "Persisted as pending", null);
                }
            }
            run.event("ready", null, null, "Batch queued; workers pause before each claim", null);
            if (o.scenario().equals("locking")) {
                run.lockedJobId = repository.findByJobType(run.type).getFirst().id();
                lock = dataSource.getConnection();
                lock.setAutoCommit(false);
                try (PreparedStatement ps = lock.prepareStatement("SELECT id FROM jobs WHERE id = ? FOR UPDATE")) {
                    ps.setLong(1, run.lockedJobId);
                    try (var rs = ps.executeQuery()) { if (!rs.next()) throw new IllegalStateException("Lock target missing"); }
                }
                run.event("locked", run.lockedJobId, null, "A separate transaction holds this row lock", null);
            }
            if (o.scenario().equals("lease-expiry")) {
                Thread.sleep(o.claimDelayMs());
                Job abandoned = new ObservedRepository(run).claim("abandoned-worker", o.leaseSeconds()).orElseThrow();
                run.event("abandoned", abandoned.id(), "abandoned-worker", "Worker deliberately stops without heartbeat or acknowledgement", abandoned.leaseExpiresAt());
                while (true) {
                    ensureWithinDeadline(run);
                    Job job = repository.findById(abandoned.id()).orElseThrow();
                    if (job.status().equals("pending") && job.attempts() == 1) {
                        run.recovered = true;
                        run.event("recovered", job.id(), null, "Scheduled reaper returned the expired job to pending; attempts = 1", null);
                        break;
                    }
                    Thread.sleep(100);
                }
            }
            JobHandler upstream = new SimulatedApiCallHandler(upstreamUrl, java.time.Duration.ofSeconds(15));
            TokenBucket bucket = new TokenBucket(1, 1.0);
            JobHandler handler = job -> {
                if (o.scenario().equals("rate-limit")) bucket.acquireOrThrow();
                upstream.handle(job);
            };
            for (int i = 0; i < o.workerCount(); i++) {
                String workerId = "lab-" + run.id.substring(0, 8) + "-worker-" + (i + 1);
                workers.add(executor.submit(() -> {
                    try (Worker worker = new Worker(new ObservedRepository(run), Map.of(run.type, handler),
                            workerId, o.leaseSeconds(), new BackoffCalculator(1, 5), o.maxAttempts(), metrics)) {
                        while (run.status.equals("running")) {
                            ensureWithinDeadline(run);
                            if (terminal(repository.findByJobType(run.type))) return null;
                            Thread.sleep(o.claimDelayMs());
                            worker.processOnce();
                        }
                    }
                    return null;
                }));
            }
            if (lock != null) {
                Thread.sleep(o.claimDelayMs() + 2500L);
                lock.rollback();
                lock.close();
                lock = null;
                run.event("unlocked", run.lockedJobId, null, "Lock released; the skipped job can now be claimed", null);
            }
            for (Future<?> worker : workers) worker.get(
                    Math.max(1, java.time.Duration.between(Instant.now(), run.startedAt.plusSeconds(180)).toMillis()),
                    TimeUnit.MILLISECONDS);
            run.checks = evaluate(run, repository.findByJobType(run.type));
            run.status = run.checks.stream().allMatch(Check::passed) ? "passed" : "failed";
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            run.error = "Test stopped: " + (e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
            run.status = "failed";
            workers.forEach(worker -> worker.cancel(true));
            run.event("error", null, null, run.error, null);
        } finally {
            if (lock != null) try { lock.rollback(); lock.close(); } catch (Exception ignored) { /* Pool closes on shutdown. */ }
            run.finishedAt = Instant.now();
            run.event("finished", null, null, "Test " + run.status, null);
        }
    }

    private static void ensureWithinDeadline(Run run) {
        if (Instant.now().isAfter(run.startedAt.plusSeconds(180))) throw new IllegalStateException("Three-minute test limit reached");
    }

    private static boolean terminal(List<Job> jobs) {
        return !jobs.isEmpty() && jobs.stream().allMatch(job -> Set.of("completed", "dead_letter").contains(job.status()));
    }

    private List<Check> evaluate(Run run, List<Job> jobs) {
        List<Check> checks = new ArrayList<>();
        String scenario = run.options.scenario();
        boolean retry = scenario.equals("retry");
        checks.add(new Check(retry ? "Failure budget exhausted" : "All jobs completed",
                jobs.stream().allMatch(job -> retry ? job.status().equals("dead_letter")
                        && job.attempts() == run.options.maxAttempts() : job.status().equals("completed")),
                retry ? "Expected dead_letter at " + run.options.maxAttempts() + " failed attempts" : jobs.size() + " persisted job(s)"));
        checks.add(new Check("Worker concurrency respected", run.peakClaimed.get() <= run.options.workerCount(),
                "Peak " + run.peakClaimed.get() + " claimed / " + run.options.workerCount() + " selected workers"));
        switch (scenario) {
            case "batch" -> checks.add(new Check("Waiting and processing overlapped", run.queueOverlap,
                    "Observed pending jobs while other jobs were claimed"));
            case "idempotency" -> checks.add(new Check("One job for repeated submissions",
                    jobs.size() == 1 && run.returnedIds.size() == run.options.jobCount()
                            && run.returnedIds.stream().distinct().count() == 1,
                    run.returnedIds.size() + " concurrent submissions returned one job ID"));
            case "locking" -> {
                checks.add(new Check("Locked row skipped", run.skippedLock, "Other jobs were claimed while the first row was locked"));
                checks.add(new Check("Each job claimed exactly once", jobs.stream().allMatch(job -> run.claimCounts.getOrDefault(job.id(), 0) == 1),
                        "Compared successful claim events for every job"));
            }
            case "heartbeat" -> {
                checks.add(new Check("Lease renewed during processing", run.heartbeats.get() >= 2, run.heartbeats.get() + " successful renewals"));
                checks.add(new Check("No recovery or extra claim", jobs.getFirst().attempts() == 0 && run.claimCounts.getOrDefault(jobs.getFirst().id(), 0) == 1,
                        "Work ran longer than its original lease and finished with zero failed attempts"));
            }
            case "lease-expiry" -> checks.add(new Check("Expired lease recovered", run.recovered
                    && jobs.getFirst().attempts() == 1 && run.claimCounts.getOrDefault(jobs.getFirst().id(), 0) == 2,
                    "Abandoned claim → scheduled reaper → recovery worker"));
            case "rate-limit" -> checks.add(new Check("Throttle preserves failure budget", run.deferrals.get() > 0
                    && jobs.stream().allMatch(job -> job.attempts() == 0), run.deferrals.get() + " local deferrals; zero failed attempts"));
            case "retry" -> checks.add(new Check("Retries actually claimed", run.claimCounts.getOrDefault(jobs.getFirst().id(), 0) == run.options.maxAttempts(),
                    "Each failed upstream call used one attempt"));
            default -> { }
        }
        return List.copyOf(checks);
    }

    private final class ObservedRepository extends JobsRepository {
        private final Run run;
        ObservedRepository(Run run) { super(dataSource); this.run = run; }
        @Override public Optional<Job> claim(String workerId, int leaseSeconds) {
            Optional<Job> result = repository.claimForJobType(workerId, leaseSeconds, run.type);
            result.ifPresent(job -> {
                synchronized (run) {
                    run.claimCounts.merge(job.id(), 1, Integer::sum);
                    boolean lockHeld = run.events.stream().anyMatch(event -> event.kind().equals("locked"))
                            && run.events.stream().noneMatch(event -> event.kind().equals("unlocked"));
                    if (lockHeld && job.id() != run.lockedJobId) run.skippedLock = true;
                    List<Job> jobs = repository.findByJobType(run.type);
                    int claimed = (int) jobs.stream().filter(j -> j.status().equals("claimed")).count();
                    run.peakClaimed.accumulateAndGet(claimed, Math::max);
                    if (claimed > 0 && jobs.stream().anyMatch(j -> j.status().equals("pending"))) run.queueOverlap = true;
                    run.event("claimed", job.id(), workerId, "Claim acquired atomically with SKIP LOCKED", job.leaseExpiresAt());
                }
            });
            return result;
        }
        @Override public boolean heartbeat(long id, String worker, int seconds) {
            boolean renewed = repository.heartbeat(id, worker, seconds);
            if (renewed) {
                run.heartbeats.incrementAndGet();
                run.event("heartbeat", id, worker, "Lease extended by " + seconds + " seconds",
                        repository.findById(id).orElseThrow().leaseExpiresAt());
            }
            return renewed;
        }
        @Override public boolean markCompleted(long id, String worker) {
            boolean result = repository.markCompleted(id, worker);
            if (result) run.event("completed", id, worker, "Handler completed; acknowledgement accepted", null);
            return result;
        }
        @Override public boolean markFailed(long id, String worker, int delay, int attempts) {
            boolean result = repository.markFailed(id, worker, delay, attempts);
            if (result) {
                Job job = repository.findById(id).orElseThrow();
                run.event(job.status().equals("dead_letter") ? "dead_letter" : "retry", id, worker,
                        "Failed attempt " + job.attempts() + "; " + (job.status().equals("pending") ? "retry in " + delay + " seconds" : "failure budget exhausted"), null);
            }
            return result;
        }
        @Override public boolean deferRateLimited(long id, String worker, int delay) {
            boolean result = repository.deferRateLimited(id, worker, delay);
            if (result) {
                run.deferrals.incrementAndGet();
                run.event("deferred", id, worker, "Local throttle; available again in " + delay + " seconds; attempts unchanged", null);
            }
            return result;
        }
    }

    private static final class Run {
        final String id = UUID.randomUUID().toString();
        final String type = "sluice-lab:" + id;
        final Options options;
        final Instant startedAt = Instant.now();
        final CopyOnWriteArrayList<Event> events = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<Long> returnedIds = new CopyOnWriteArrayList<>();
        final Map<Long, Integer> claimCounts = new ConcurrentHashMap<>();
        final AtomicLong sequence = new AtomicLong();
        final AtomicInteger peakClaimed = new AtomicInteger();
        final AtomicInteger heartbeats = new AtomicInteger();
        final AtomicInteger deferrals = new AtomicInteger();
        volatile String status = "running";
        volatile String error;
        volatile Instant finishedAt;
        volatile List<Check> checks = List.of();
        volatile long lockedJobId;
        volatile boolean queueOverlap;
        volatile boolean skippedLock;
        volatile boolean recovered;
        Run(Options options) { this.options = options; }
        synchronized void event(String kind, Long jobId, String worker, String detail, Instant lease) {
            if (events.size() < 2000) events.add(new Event(sequence.incrementAndGet(), Instant.now(), kind, jobId, worker, detail, lease));
        }
    }

    @PreDestroy public void close() { executor.shutdownNow(); }
}
