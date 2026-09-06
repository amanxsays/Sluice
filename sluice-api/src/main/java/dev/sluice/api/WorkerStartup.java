package dev.sluice.api;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import dev.sluice.core.BackoffCalculator;
import dev.sluice.core.JobHandler;
import dev.sluice.core.JobsRepository;
import dev.sluice.core.SimulatedApiCallHandler;
import dev.sluice.core.Worker;

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

    final JobsRepository repository;

    public WorkerStartup(JobsRepository repository){
        this.repository=repository;
    }

    @Override
    public void run(String... args) {
        Map<String, JobHandler> handlers = Map.of("call-api", new SimulatedApiCallHandler(mockUpstreamUrl));
        for (int i = 0; i < workerCount; i++) {
            String workerId = "worker-" + i;
            Worker worker = new Worker(repository, handlers, workerId, leaseSeconds, new BackoffCalculator(1, 60), maxAttempts);
            Thread thread = new Thread(worker::run);
            thread.setDaemon(true);
            thread.start();
        }
    }
}
