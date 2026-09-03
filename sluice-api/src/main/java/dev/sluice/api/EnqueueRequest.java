package dev.sluice.api;

public record EnqueueRequest(String jobType, String payload, String idempotencyKey) {
}