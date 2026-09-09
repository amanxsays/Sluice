package dev.sluice.core;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.postgresql.ds.PGSimpleDataSource;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

public class BenchmarkRunner {
    
    private static boolean allCompleted(JobsRepository repository, List<Long> jobIds) {
        for (long id : jobIds) {
            Job job = repository.findById(id).orElseThrow();
            if (!job.status().equals("completed")) {
                return false;
            }
        }
        return true;
    }

    private static void runPhase(int workerCount, int jobCount, DataSource dataSource, String mockUpstreamUrl) {
        JobsRepository repository = new JobsRepository(dataSource);
        List<Long> jobIds = new ArrayList<>();

        for (int i = 0; i < jobCount; i++) {
            Job job = repository.enqueue("call-api", "{\"latencyMs\":50,\"shouldFail\":false,\"retryAfterSeconds\":0}", null, 0);
            jobIds.add(job.id());
        }

        Map<String, JobHandler> handlers = Map.of("call-api", new SimulatedApiCallHandler(mockUpstreamUrl));
        List<Worker> workers = new ArrayList<>();

        for (int i = 0; i < workerCount; i++) {
            Worker worker = new Worker(repository, handlers, "bench-worker-" + i, 30, new BackoffCalculator(1, 60), 5,new SimpleMeterRegistry());
            workers.add(worker);
            Thread thread = new Thread(worker::run);
            thread.setDaemon(true);
            thread.start();
        }

        Instant start = Instant.now();
        while (!allCompleted(repository, jobIds)) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                break;
            }
        }
        Instant end = Instant.now();

        for (Worker worker : workers) {
            worker.stop();
        }

        long elapsedMillis = Duration.between(start, end).toMillis();
        double throughput = jobCount / (elapsedMillis / 1000.0);
        System.out.printf("Workers: %d, Jobs: %d, Elapsed: %dms, Throughput: %.2f jobs/sec%n",
                workerCount, jobCount, elapsedMillis, throughput);
        printP95Latency(repository, jobIds);

    }

    private static void printP95Latency(JobsRepository repository, List<Long> jobIds) {
        List<Long> durationsMillis = new ArrayList<>();

        for (long id : jobIds) {
            Job job = repository.findById(id).orElseThrow();
            long duration = Duration.between(job.createdAt(), job.updatedAt()).toMillis();
            durationsMillis.add(duration);
        }

        Collections.sort(durationsMillis);
        int index = (int) Math.ceil(0.95 * durationsMillis.size()) - 1;
        long p95 = durationsMillis.get(index);

        System.out.println("p95 latency: " + p95 + "ms");
    }

    public static void main(String[] args) throws Exception {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl("jdbc:postgresql://localhost:5432/postgres");
        dataSource.setUser("postgres");
        dataSource.setPassword("postgres");

        String mockUpstreamUrl = "http://localhost:8081/simulate";
        int[] workerCounts = {1, 2, 4, 8};

        for (int workerCount : workerCounts) {
            try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement()) {
                stmt.execute("TRUNCATE TABLE jobs RESTART IDENTITY");
            }

            runPhase(workerCount, 100, dataSource, mockUpstreamUrl);
        }
    }

}
