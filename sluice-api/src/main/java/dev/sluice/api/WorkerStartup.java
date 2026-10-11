package dev.sluice.api;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import dev.sluice.core.BackoffCalculator;
import dev.sluice.core.JobHandler;
import dev.sluice.core.JobsRepository;
import dev.sluice.core.SimulatedApiCallHandler;
import dev.sluice.core.Worker;
import io.micrometer.core.instrument.MeterRegistry;

import dev.sluice.core.TokenBucket;

@Component 
public class WorkerStartup implements CommandLineRunner{
    @Value("${sluice.worker.count}")
    private int workerCount;

    @Value("${sluice.worker.lease-seconds}")
    private int leaseSeconds;

    @Value("${sluice.worker.max-attempts}")
    private int maxAttempts;

    @Value("${sluice.mock-upstream.url}")
    private String mockUpstreamUrl;

    @Value("${sluice.rate-limit.capacity}")
    private int bucketCapacity;

    @Value("${sluice.rate-limit.refill-per-second}")
    private double refillPerSecond;

    final JobsRepository repository;
    final MeterRegistry meterRegistry;
    private final String instanceId = UUID.randomUUID().toString();

    private final List<Worker> workers = new ArrayList<>();
    private final List<Thread> workerThreads = new ArrayList<>();

    public WorkerStartup(JobsRepository repository, MeterRegistry meterRegistry){
        this.repository = repository;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void run(String... args) {
        TokenBucket bucket = new TokenBucket(bucketCapacity, refillPerSecond);
        JobHandler upstreamHandler = new SimulatedApiCallHandler(mockUpstreamUrl);

        JobHandler limitedHandler = job -> {
            bucket.acquireOrThrow();//here actual rate limiting is present
            upstreamHandler.handle(job);
        };

        Map<String, JobHandler> handlers = Map.of("call-api", limitedHandler);
        for (int i = 0; i < workerCount; i++) {
            String workerId = instanceId + "-worker-" + i;

            Worker worker = new Worker(
                    repository,
                    handlers,
                    workerId,
                    leaseSeconds,
                    new BackoffCalculator(1, 60),
                    maxAttempts,
                    meterRegistry
            );

            workers.add(worker);

            Thread thread = new Thread(worker::run, workerId);
            thread.setDaemon(true);

            workerThreads.add(thread);
            thread.start();
        }
    }
    @PreDestroy
    public void stopWorkers() {
        workers.forEach(Worker::stop);
        workerThreads.forEach(Thread::interrupt);
    }
}