package dev.sluice.core;

import java.util.Map;
import java.util.Optional;

import io.micrometer.core.instrument.MeterRegistry;

public class Worker {
    private final JobsRepository jobsRepository;
    private final Map<String, JobHandler> handlers;
    private final String workerId;
    private final int leaseSeconds;
    private final BackoffCalculator backoffCalculator;
    private final int maxAttempts;
    private volatile boolean running = true;
    private final MeterRegistry meterRegistry;

    public Worker(JobsRepository jobsRepository, Map<String, JobHandler> handlers, String workerId, int leaseSeconds, BackoffCalculator backoffCalculator, int maxAttempts,MeterRegistry meterRegistry) {
        this.jobsRepository = jobsRepository;
        this.handlers = handlers;
        this.workerId = workerId;
        this.leaseSeconds = leaseSeconds;
        this.backoffCalculator = backoffCalculator;
        this.maxAttempts = maxAttempts;
        this.meterRegistry = meterRegistry;
    }

    public boolean processOnce() {
        Optional<Job> claimed = jobsRepository.claim(workerId, leaseSeconds);
        if (claimed.isEmpty()) {
            return false;
        }
        Job job = claimed.get();
        JobHandler handler = handlers.get(job.jobType());
        try {
            if (handler == null) {
                throw new IllegalStateException("No handler registered for job type: " + job.jobType());
            }
            handler.handle(job);
            jobsRepository.markCompleted(job.id(), workerId);
            meterRegistry.counter("sluice.worker.jobs", "outcome", "completed").increment();
        } catch (RateLimitedException e) {
            jobsRepository.markFailed(job.id(), workerId, e.retryAfterSeconds(), maxAttempts);
            meterRegistry.counter("sluice.worker.jobs", "outcome", "failed").increment();
        } catch (Exception e) {
            int backoffSeconds = backoffCalculator.nextDelaySeconds(job.attempts());
            jobsRepository.markFailed(job.id(), workerId, backoffSeconds, maxAttempts);
            meterRegistry.counter("sluice.worker.jobs", "outcome", "failed").increment();
        }
        meterRegistry.counter("sluice.worker.jobs", "outcome", "no_handler").increment();
        return true;
    }

    public void run(){
        while (running) {
            boolean result = processOnce();
            if(!result){
                try {
                    Thread.sleep(2000);
                }
                catch (InterruptedException e){
                    break;
                }
            }
        }
    } 

    public void stop() { running = false; }

}
