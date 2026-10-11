package dev.sluice.core;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

public class Worker implements AutoCloseable {

    private static final Logger log =
            LoggerFactory.getLogger(Worker.class);

    private final JobsRepository jobsRepository;
    private final Map<String, JobHandler> handlers;
    private final String workerId;
    private final int leaseSeconds;
    private final BackoffCalculator backoffCalculator;
    private final int maxAttempts;
    private final MeterRegistry meterRegistry;
    private final ScheduledThreadPoolExecutor heartbeatExecutor;

    private volatile boolean running = true;

    public Worker(
            JobsRepository jobsRepository,
            Map<String, JobHandler> handlers,
            String workerId,
            int leaseSeconds,
            BackoffCalculator backoffCalculator,
            int maxAttempts,
            MeterRegistry meterRegistry) {

        if (leaseSeconds <= 0) {
            throw new IllegalArgumentException(
                    "leaseSeconds must be positive");
        }

        this.jobsRepository = jobsRepository;
        this.handlers = handlers;
        this.workerId = workerId;
        this.leaseSeconds = leaseSeconds;
        this.backoffCalculator = backoffCalculator;
        this.maxAttempts = maxAttempts;
        this.meterRegistry = meterRegistry;

        this.heartbeatExecutor =
                new ScheduledThreadPoolExecutor(1, task -> {
                    Thread thread =
                            new Thread(task, workerId + "-heartbeat");
                    thread.setDaemon(true);
                    return thread;
                });

        this.heartbeatExecutor.setRemoveOnCancelPolicy(true);
    }

    public boolean processOnce() {
        Optional<Job> claimed =
                jobsRepository.claim(workerId, leaseSeconds);

        if (claimed.isEmpty()) {
            return false;
        }

        Job job = claimed.get();
        JobHandler handler = handlers.get(job.jobType());

        Timer.Sample sample = Timer.start(meterRegistry);
        Heartbeat heartbeat = new Heartbeat(job.id());

        try {
            // Closing this resource stops heartbeats before acknowledging.
            try (heartbeat) {
                if (handler == null) {
                    count("no_handler");

                    throw new IllegalStateException(
                            "No handler registered for job type: "
                                    + job.jobType());
                }

                handler.handle(job);
            }

            if (heartbeat.hasLease()
                    && jobsRepository.markCompleted(job.id(), workerId)) {
                count("completed");
            } else {
                recordLeaseLost(job);
            }

                } catch (RateLimitedException e) {
            if (e.isLocalThrottle()) {
                if (heartbeat.hasLease()
                        && jobsRepository.deferRateLimited(
                                job.id(),
                                workerId,
                                e.retryAfterSeconds())) {

                    count("deferred");
                } else {
                    recordLeaseLost(job);
                }
            } else {
                fail(
                        job,
                        e.retryAfterSeconds(),
                        heartbeat.hasLease()
                );
            }

        } catch (Exception e) {
            log.warn("Job " + job.id() + " failed", e);

            fail(
                    job,
                    backoffCalculator.nextDelaySeconds(job.attempts()),
                    heartbeat.hasLease()
            );

        } finally {
            sample.stop(
                    meterRegistry.timer("sluice.worker.job.duration"));
        }

        return true;
    }

    private void fail(
            Job job,
            int backoffSeconds,
            boolean hasLease) {

        if (hasLease && jobsRepository.markFailed(
                job.id(), workerId, backoffSeconds, maxAttempts)) {
            count("failed");
        } else {
            recordLeaseLost(job);
        }
    }

    private void count(String outcome) {
        meterRegistry.counter(
                "sluice.worker.jobs",
                "outcome",
                outcome
        ).increment();
    }

    private void recordLeaseLost(Job job) {
        log.warn(
                "Worker {} no longer owns job {}",
                workerId,
                job.id()
        );

        count("lease_lost");
    }

    private final class Heartbeat implements AutoCloseable {

        private final long jobId;
        private final ScheduledFuture<?> task;

        private boolean active = true;
        private boolean leaseLost;

        Heartbeat(long jobId) {
            this.jobId = jobId;

            long intervalMs =
                    Math.max(100L, leaseSeconds * 1000L / 3);

            this.task = heartbeatExecutor.scheduleWithFixedDelay(
                    this::renew,
                    intervalMs,
                    intervalMs,
                    TimeUnit.MILLISECONDS
            );
        }

        private synchronized void renew() {
            if (!active || leaseLost) {
                return;
            }

            try {
                leaseLost = !jobsRepository.heartbeat(
                        jobId,
                        workerId,
                        leaseSeconds
                );

                if (leaseLost) {
                    log.warn(
                            "Heartbeat rejected for job {} owned by {}",
                            jobId,
                            workerId
                    );
                }

            } catch (RuntimeException e) {
                // Keep later heartbeat attempts running.
                log.error("Heartbeat failed for job " + jobId, e);
            }
        }

        synchronized boolean hasLease() {
            return !leaseLost;
        }

        @Override
        public synchronized void close() {
            active = false;
            task.cancel(false);
        }
    }

    public void run() {
        try {
            while (running
                    && !Thread.currentThread().isInterrupted()) {

                boolean processed;

                try {
                    processed = processOnce();
                } catch (RuntimeException e) {
                    log.error(
                            "Worker " + workerId + " iteration failed",
                            e
                    );
                    processed = false;
                }

                if (!processed) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } finally {
            close();
        }
    }

    public void stop() {
        running = false;
    }

    @Override
    public void close() {
        running = false;
        heartbeatExecutor.shutdownNow();
    }
}