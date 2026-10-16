package com.ticketing.queue.application;

import com.ticketing.seat.application.QueueAdmissionVerifier;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * {@link QueueAdmissionVerifier} 구현 — 입장 시 세운 사용자 단위 Redis 키로 판단한다.
 * 좌석 점유 1건마다 Redis EXISTS 1회.
 */
@Component
public class RedisQueueAdmissionVerifier implements QueueAdmissionVerifier {

    private final VirtualQueueService queueService;

    public RedisQueueAdmissionVerifier(VirtualQueueService queueService) {
        this.queueService = queueService;
    }

    @Override
    public boolean isAdmitted(UUID scheduleId, UUID userId) {
        return queueService.isAdmitted(scheduleId, userId.toString());
    }
}
