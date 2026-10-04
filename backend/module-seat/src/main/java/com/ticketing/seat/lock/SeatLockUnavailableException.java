package com.ticketing.seat.lock;

import java.util.UUID;

/**
 * 좌석 분산락 획득 실패.
 *
 * <p>
 *   다른 사용자가 점유 중이거나 tryLock waitTime 안에 락을 얻지 못한 경우 발생.
 *   {@link com.ticketing.seat.application.SeatHoldService} 가 잡아서
 *   {@link com.ticketing.common.error.BusinessException SEAT_ALREADY_HELD} 로 변환한다.
 * </p>
 *
 * <p>
 *   왜 checked / runtime 가 아닌 runtime 으로 두는가:
 *   호출 스택의 모든 중간 레이어에 throws 를 강제하면 보일러플레이트가 늘고, 분산락 실패는
 *   "예외 상황" 보다 "비즈니스 의미 있는 충돌" 이므로 BusinessException 으로 다시 래핑되는 것이 자연스럽다.
 * </p>
 */
public class SeatLockUnavailableException extends RuntimeException {

    private final UUID failedSeatId;

    public SeatLockUnavailableException(UUID failedSeatId, String message) {
        super(message);
        this.failedSeatId = failedSeatId;
    }

    public UUID failedSeatId() {
        return failedSeatId;
    }
}
