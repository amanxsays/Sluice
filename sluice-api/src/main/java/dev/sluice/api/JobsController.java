package dev.sluice.api;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import dev.sluice.core.Job;
import dev.sluice.core.JobsRepository;

@RestController
public class JobsController {

    private final JobsRepository jobsRepository;

    public JobsController(JobsRepository jobsRepository) {
        this.jobsRepository = jobsRepository;
    }

    @PostMapping("/jobs")
    public Job enqueue(@RequestBody EnqueueRequest request){
        return jobsRepository.enqueue(request.jobType(), request.payload(), request.idempotencyKey(),0);
    }
}
