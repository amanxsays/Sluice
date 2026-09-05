package dev.sluice.core;

import java.time.Instant;

public record Job(
        long id,
        String jobType,
        String payload,
        String idempotencyKey,
        String status,
        String claimedBy,
        Instant claimedAt,
        Instant createdAt,
        Instant updatedAt,
        Instant leaseExpiresAt,
        int attempts,
        Instant availableAt,
        int priority
) {}