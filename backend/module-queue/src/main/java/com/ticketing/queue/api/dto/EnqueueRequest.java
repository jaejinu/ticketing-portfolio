package com.ticketing.queue.api.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * 대기열 진입 요청.
 *
 * <pre>
 *   POST /api/v1/queue/enqueue
 *   { "scheduleId": "uuid" }
 * </pre>
 */
public record EnqueueRequest(@NotNull UUID scheduleId) {
}
