package com.ticketing.seat.application;

import java.util.UUID;

/**
 * "이 사용자가 이 회차의 대기열을 통과했는가" 조회 포트.
 *
 * <h2>왜 인터페이스(포트) 인가</h2>
 * <p>
 *   대기열 상태는 module-queue 가 Redis 에 들고 있다. seat → queue 를 직접 참조하지 않고
 *   이 포트만 정의해 두면 구현은 queue 가 제공한다 ({@link CurrentPriceProvider} 와 같은 의존성 역전).
 * </p>
 *
 * <p>
 *   {@code app.seat.hold.require-queue-admission=true}(기본값) 인데 구현체가 없으면
 *   {@link SeatHoldService} 는 점유를 거부한다 — 검사를 조용히 건너뛰지 않기 위함.
 * </p>
 */
public interface QueueAdmissionVerifier {

    /**
     * @return 사용자가 해당 회차에 입장(ADMITTED) 상태이고 입장 유효 시간이 남아 있으면 true
     */
    boolean isAdmitted(UUID scheduleId, UUID userId);
}
