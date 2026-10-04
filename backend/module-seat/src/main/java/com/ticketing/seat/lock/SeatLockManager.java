package com.ticketing.seat.lock;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 좌석 단위 분산락 매니저.
 *
 * <h2>책임</h2>
 * <ul>
 *   <li>단일/다중 좌석 락 획득 (Redisson tryLock)</li>
 *   <li>다중 좌석은 seatId ASC 정렬 후 순차 획득 → 데드락 회피</li>
 *   <li>중간 실패 시 이미 잡힌 락 역순 보상 해제</li>
 *   <li>성공 시 leaseTime(=hold TTL) 동안 자동 보유, 명시 해제 가능</li>
 * </ul>
 *
 * <h2>데드락 회피 패턴</h2>
 * <pre>
 *   요청자 A: seatIds = [s3, s1, s5]   →  정렬 [s1, s3, s5]
 *   요청자 B: seatIds = [s5, s3, s1]   →  정렬 [s1, s3, s5]
 *   두 요청 모두 동일한 순서로 락을 잡으므로 순환 대기 불가 → 데드락 0.
 * </pre>
 *
 * <h2>왜 인터페이스로 추상화하나</h2>
 * <p>
 *   Redisson 의존을 application 레이어에서 분리. 통합 테스트는 Redisson 그대로 사용하지만,
 *   향후 단위 테스트에선 in-memory 구현으로 갈아끼울 수 있다.
 * </p>
 */
public interface SeatLockManager {

    /**
     * 좌석 N 개의 락을 ASC 순차로 획득 시도.
     *
     * @param seatIds   요청 좌석 id (정렬 전 상태여도 무관 — 내부에서 ASC 정렬)
     * @param waitTime  각 좌석의 tryLock 최대 대기 시간 (예: 200ms)
     * @param leaseTime 획득 후 자동 유지 시간 (= hold TTL, 예: 5분)
     * @return 모든 좌석 락 획득 성공 시 핸들 반환. 단 하나라도 실패하면 빈 Optional 같은 의미로
     *         {@link SeatLockUnavailableException} 을 던진다. (실패 시 이미 잡은 락은 내부에서 보상 해제 완료)
     * @throws SeatLockUnavailableException 어느 좌석이 다른 사용자에 의해 점유 중인 경우
     * @throws InterruptedException Redisson tryLock 대기 도중 인터럽트
     */
    SeatLockHandle tryAcquireAll(List<UUID> seatIds, Duration waitTime, Duration leaseTime)
            throws InterruptedException;

    /**
     * 명시 해제 — holder 의 명시 release / 결제 saga 의 hold 해소 등에서 사용.
     *
     * <p>
     *   tryAcquireAll 로 락을 잡은 스레드와 release 호출 스레드가 다를 수 있으므로
     *   RLock.unlock() 대신 forceUnlock 의미로 동작한다. holder 검증은 application 레이어
     *   ({@link com.ticketing.seat.application.SeatHoldService}) 가 책임지고 들어왔다고 가정.
     * </p>
     *
     * <p>
     *   결과는 swallow — 이미 만료/해제된 락에 호출해도 예외 없이 통과.
     *   "해제 후 상태" 만 보장하면 되므로 정합 회복 관점에서 멱등이 자연스럽다.
     * </p>
     */
    void forceReleaseAll(List<UUID> seatIds);
}
