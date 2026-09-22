package dev.sluice.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TokenBucketTest {
    @Test
    void allowsInitialBurstUpToCapacity() throws Exception {
        TokenBucket bucket = new TokenBucket(3, 1.0, () -> 0L);

        for (int i = 0; i < 3; i++) {
            bucket.acquireOrThrow();
        }

        assertThrows(RateLimitedException.class, bucket::acquireOrThrow);
    }

    @Test
    void rejectsWhenEmptyAndRoundsDelayUp() throws Exception {
        TokenBucket bucket = new TokenBucket(1, 0.4, () -> 0L);
        bucket.acquireOrThrow();

        RateLimitedException ex = assertThrows(
                RateLimitedException.class, bucket::acquireOrThrow);

        assertTrue(ex.isLocalThrottle());
        assertEquals(3, ex.retryAfterSeconds());
    }

    @Test
    void refillsFractionalTokensUsingElapsedTime() throws Exception {
        AtomicLong clock = new AtomicLong();
        TokenBucket bucket = new TokenBucket(1, 2.0, clock::get);

        bucket.acquireOrThrow();

        clock.set(250_000_000L);
        assertThrows(RateLimitedException.class, bucket::acquireOrThrow);

        clock.set(500_000_000L);
        bucket.acquireOrThrow();
        assertThrows(RateLimitedException.class, bucket::acquireOrThrow);
    }

    @Test
    void idleTimeCannotIncreaseBurstBeyondCapacity() throws Exception {
        AtomicLong clock = new AtomicLong();
        TokenBucket bucket = new TokenBucket(2, 5.0, clock::get);

        clock.set(60_000_000_000L);

        bucket.acquireOrThrow();
        bucket.acquireOrThrow();
        assertThrows(RateLimitedException.class, bucket::acquireOrThrow);
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> new TokenBucket(0, 1));

        for (double rate : new double[] {
                0, -1, Double.NaN, Double.POSITIVE_INFINITY
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> new TokenBucket(1, rate));
        }
    }
}