package dev.sluice.core;

import java.util.Objects;
import java.util.function.LongSupplier;

public final class TokenBucket {
    private final int capacity;
    private final double refillPerSecond;
    private final LongSupplier nanoClock;
    private double tokens;
    private long lastRefillNanos;

    public TokenBucket(int capacity, double refillPerSecond) {
        this(capacity, refillPerSecond, System::nanoTime);
    }

    TokenBucket(int capacity, double refillPerSecond, LongSupplier nanoClock) {
        if (capacity < 1 || !Double.isFinite(refillPerSecond) || refillPerSecond <= 0) {
            throw new IllegalArgumentException(
                    "Capacity and refill rate must be positive and finite");
        }
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.nanoClock = Objects.requireNonNull(nanoClock);
        this.tokens = capacity;
        this.lastRefillNanos = nanoClock.getAsLong();
    }

    //performs refill, checking, and consumption under one lock
    public synchronized void acquireOrThrow() throws RateLimitedException {
        long now = nanoClock.getAsLong();
        long elapsedNanos = now - lastRefillNanos;

        if (elapsedNanos > 0) {
            tokens = Math.min(capacity,
                    tokens + elapsedNanos / 1_000_000_000.0 * refillPerSecond);
            lastRefillNanos = now;
        }

        if (tokens >= 1.0) {
            tokens -= 1.0;
            return;
        }

        double waitSeconds = Math.ceil((1.0 - tokens) / refillPerSecond);
        int retryAfterSeconds = (int) Math.min(Integer.MAX_VALUE, waitSeconds);

        throw RateLimitedException.localThrottle(Math.max(1, retryAfterSeconds));
    }
}