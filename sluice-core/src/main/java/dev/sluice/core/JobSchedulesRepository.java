package dev.sluice.core;

import javax.sql.DataSource;

public class JobSchedulesRepository {
    private final DataSource dataSource;

    public JobSchedulesRepository(DataSource dataSource){
        this.dataSource=dataSource;
    }
}
