package dev.sluice.core;

public class ConsoleLoggingHandler implements JobHandler {
    public void handle(Job job) throws Exception{
        System.out.println("Processing job " + job.id() + " of type " + job.jobType());
    }
}
