package dev.sluice.core;


public class RateLimitedException extends Exception{
    private int retryAfterSeconds;

    public RateLimitedException(int retryAfterSeconds){
        this.retryAfterSeconds=retryAfterSeconds;
    }

    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }
}