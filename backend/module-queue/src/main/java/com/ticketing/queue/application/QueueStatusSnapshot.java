package com.ticketing.queue.application;

import java.util.UUID;

/**
 * 대기열 상태 단건 스냅샷.
 *
 * @param state                 WAITING / ADMITTED / EXPIRED
 * @param ticket                ticket id
 * @param scheduleId            회차 id
 * @param position              waiting 큐 안 0-based 위치. ADMITTED/EXPIRED 면 0.
 * @param estimatedWaitSeconds  position / admit-rate 로 계산된 예상 대기 시간 (초).
 *                              ADMITTED/EXPIRED 면 0.
 */
public record QueueStatusSnapshot(
        String state,
        UUID ticket,
        UUID scheduleId,
        long position,
        long estimatedWaitSeconds
) {
}
