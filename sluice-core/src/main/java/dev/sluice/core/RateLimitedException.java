package dev.sluice.core;

public class RateLimitedException extends Exception {
    private final int retryAfterSeconds;
    private final boolean localThrottle;

    public RateLimitedException(int retryAfterSeconds) {
        this(retryAfterSeconds, false);
    }

    private RateLimitedException(int retryAfterSeconds, boolean localThrottle) {
        this.retryAfterSeconds = retryAfterSeconds;
        this.localThrottle = localThrottle;
    }

    public static RateLimitedException localThrottle(int retryAfterSeconds) {
        return new RateLimitedException(retryAfterSeconds, true);
    }

    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public boolean isLocalThrottle() {
        return localThrottle;
    }
}