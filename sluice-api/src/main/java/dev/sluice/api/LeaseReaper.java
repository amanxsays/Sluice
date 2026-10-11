package dev.sluice.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import dev.sluice.core.JobsRepository;

@Component
public class LeaseReaper {

    private static final Logger log =
            LoggerFactory.getLogger(LeaseReaper.class);

    private final JobsRepository jobsRepository;
    private final int maxAttempts;

    public LeaseReaper(
            JobsRepository jobsRepository,
            @Value("${sluice.worker.max-attempts:5}")
            int maxAttempts) {

        this.jobsRepository = jobsRepository;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(
        fixedDelayString = "${sluice.reaper.poll-delay-ms:3000}"
    )
    public void recoverExpiredJobs() {
        try {
            int count =
                    jobsRepository.reclaimExpiredLeases(maxAttempts);

            if (count > 0) {
                log.info(
                        "Processed {} expired job leases",
                        count
                );
            }

        } catch (RuntimeException e) {
            log.error("Expired-lease recovery failed", e);
        }
    }
}