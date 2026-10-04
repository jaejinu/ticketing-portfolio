package com.ticketing.seat.lock;

import java.util.List;
import java.util.UUID;

/**
 * 다중 좌석 분산락 핸들.
 *
 * <p>
 *   {@link SeatLockManager#tryAcquireAll(List, java.time.Duration, java.time.Duration)} 의 성공 반환.
 *   호출자는 점유 트랜잭션 끝(성공/실패 무관) 시점에 {@link #releaseAll()} 또는
 *   {@link #releaseOnFailure()} 중 하나를 반드시 호출해야 한다.
 * </p>
 *
 * <h2>왜 try-with-resources 가 아닌가</h2>
 * <p>
 *   AutoCloseable 로 만들면 성공 케이스에서도 닫히면서 락이 풀려버린다. 좌석 점유는
 *   "성공 시 락 유지(TTL 5분), 실패 시 즉시 해제" 가 의도된 동작이므로 명시 메서드를 둔다.
 * </p>
 */
public interface SeatLockHandle {

    /** 락이 잡힌 좌석 id 목록 (요청 시 ASC 정렬된 순서). */
    List<UUID> seatIds();

    /**
     * 성공 트랜잭션 종료 시 명시 해제. 사용자가 hold 를 즉시 해제하려는 경우(Phase 1 의 release API)
     * 에 호출. 통상 점유 성공 시엔 호출하지 않고 TTL 만료에 맡긴다.
     */
    void releaseAll();

    /**
     * 트랜잭션 실패(좌석 도메인 충돌 / DB 오류 등) 시 잡힌 락을 즉시 해제하기 위한 보상 호출.
     * releaseAll 과 동일 동작이지만, 의미상 "성공 후 해제" vs "실패 후 보상" 을 구분해
     * 호출자가 어느 경로로 들어왔는지 로그/메트릭에 드러나도록 둔다.
     */
    void releaseOnFailure();
}
