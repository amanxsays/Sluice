package dev.sluice.core;

import java.time.Instant;

public record JobSchedule(
        long id,
        String cronExpression,
        String jobType,
        String payload,
        int priority,
        Instant nextRunAt,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt
) {}