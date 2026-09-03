package dev.sluice.core;

public class JobPersistenceException extends RuntimeException {
    public JobPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}