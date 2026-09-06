package dev.sluice.core;

public interface JobHandler {
    void handle(Job job) throws Exception;
}
