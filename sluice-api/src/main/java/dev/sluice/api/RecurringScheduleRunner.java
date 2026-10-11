package dev.sluice.api;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import dev.sluice.core.CronScheduleCalculator;
import dev.sluice.core.JobSchedule;
import dev.sluice.core.JobSchedulesRepository;
import dev.sluice.core.JobsRepository;

@Component
public class RecurringScheduleRunner {

    private static final Logger log =
            LoggerFactory.getLogger(RecurringScheduleRunner.class);

    private final JobSchedulesRepository schedulesRepository;
    private final JobsRepository jobsRepository;
    private final CronScheduleCalculator cronCalculator;

    public RecurringScheduleRunner(
            JobSchedulesRepository schedulesRepository,
            JobsRepository jobsRepository,
            CronScheduleCalculator cronCalculator) {

        this.schedulesRepository = schedulesRepository;
        this.jobsRepository = jobsRepository;
        this.cronCalculator = cronCalculator;
    }

    @Scheduled(
        fixedDelayString = "${sluice.scheduler.poll-delay-ms:2000}"
    )
    public void fireDueSchedules() {
        try {
            for (JobSchedule schedule
                    : schedulesRepository.findDueSchedules()) {

                try {
                    Instant nextRunAt = cronCalculator.nextRunAfter(
                            schedule.cronExpression(),
                            Instant.now()
                    );

                    schedulesRepository.fireSchedule(
                            schedule,
                            jobsRepository,
                            nextRunAt
                    );

                    log.info(
                            "Processed schedule {}. Next run: {}",
                            schedule.id(),
                            nextRunAt
                    );
                } catch (Exception e) {
                    log.error(
                            "Failed to fire schedule " + schedule.id(),
                            e
                    );
                }
            }
        } catch (Exception e) {
            log.error("Failed to fetch due schedules", e);
        }
    }
}