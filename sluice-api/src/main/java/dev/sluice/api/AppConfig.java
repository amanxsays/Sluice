package dev.sluice.api;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import dev.sluice.core.CronScheduleCalculator;
import dev.sluice.core.JobSchedulesRepository;
import dev.sluice.core.JobsRepository;

@Configuration
@EnableScheduling
public class AppConfig {
    
    @Bean
    public JobsRepository jobsRepository(DataSource dataSource){
        return new JobsRepository(dataSource);
    }

    @Bean
    public JobSchedulesRepository jobSchedulesRepository(
            DataSource dataSource) {
        return new JobSchedulesRepository(dataSource);
    }

    @Bean
    public CronScheduleCalculator cronScheduleCalculator() {
        return new CronScheduleCalculator();
    }
}
