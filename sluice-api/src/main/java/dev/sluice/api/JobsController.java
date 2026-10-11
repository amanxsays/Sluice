package dev.sluice.api;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import dev.sluice.core.Job;
import dev.sluice.core.JobsRepository;

@RestController
public class JobsController {

    private static final Set<String> VALID_STATUSES = Set.of(
            "pending",
            "claimed",
            "completed",
            "dead_letter"
    );

    private final JobsRepository jobsRepository;

    public JobsController(JobsRepository jobsRepository) {
        this.jobsRepository = jobsRepository;
    }

    @PostMapping("/jobs")
    public Job enqueue(@RequestBody EnqueueRequest request) {
        validateType(request.jobType());
        return jobsRepository.enqueue(
                request.jobType(),
                request.payload(),
                request.idempotencyKey(),
                0
        );
    }

    public record BatchRequest(String jobType, String payload, String idempotencyKey, int priority, int count) {}

    @PostMapping("/jobs/batch")
    public List<Job> enqueueBatch(@RequestBody BatchRequest request) {
        validateType(request.jobType());
        if (request.count() < 1 || request.count() > 100) throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Batch size must be between 1 and 100");
        try {
            if (request.payload() == null || new com.fasterxml.jackson.databind.ObjectMapper().readTree(request.payload()) == null)
                throw new IllegalArgumentException("Missing JSON payload");
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payload must be valid JSON");
        }
        String prefix = request.idempotencyKey() == null || request.idempotencyKey().isBlank()
                ? null : request.idempotencyKey().trim();
        return jobsRepository.enqueueBatch(request.jobType(), request.payload(), prefix, 0, request.count());
    }

    private static void validateType(String type) {
        if (type == null || type.isBlank() || type.startsWith("sluice-lab:")) throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Enter a job type outside the reserved sluice-lab: namespace");
    }

    @GetMapping("/jobs")
    public List<Job> list(
            @RequestParam(
                    name = "status",
                    required = false
            ) String status,

            @RequestParam(
                    name = "limit",
                    defaultValue = "50"
            ) int limit,

            @RequestParam(
                    name = "offset",
                    defaultValue = "0"
            ) int offset) {

        if (limit < 1 || limit > 100) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "limit must be between 1 and 100"
            );
        }

        if (offset < 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "offset must be zero or greater"
            );
        }

        String filter = status == null ? null : status.trim();

        if (filter != null && filter.isEmpty()) {
            filter = null;
        }

        if (filter != null && !VALID_STATUSES.contains(filter)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid job status"
            );
        }

        return jobsRepository.findRecent(filter, limit, offset);
    }

    @GetMapping("/jobs/stats")
    public Map<String, Long> stats() {
        return jobsRepository.countByStatus();
    }

    @GetMapping("/jobs/{id}")
    public ResponseEntity<Job> findById(
            @PathVariable("id") long id) {

        return jobsRepository.findById(id)
                .map(job -> ResponseEntity.ok(job))
                .orElseGet(() ->
                        ResponseEntity.notFound().build());
    }
}
