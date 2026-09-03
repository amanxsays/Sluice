package dev.sluice.api;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.sluice.core.JobsRepository;

@Configuration
public class AppConfig {
    
    @Bean
    public JobsRepository jobsRepository(DataSource dataSource){
        return new JobsRepository(dataSource);
    }
}
