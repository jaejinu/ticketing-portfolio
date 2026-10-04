package com.ticketing.events;

/**
 * Kafka 토픽 이름 상수 카탈로그.
 *
 * <p>
 *   <b>네이밍 규칙:</b> {@code <bounded-context>.<event>.v<n>}.
 *   토픽 이름 오타는 silent failure(다른 토픽으로 발행/구독) 를 낳기 때문에 반드시 이 상수만 사용한다.
 * </p>
 *
 * <p>
 *   <b>변경 가이드:</b>
 *   <ul>
 *     <li>스키마 호환 깨짐 → 새 상수 {@code SEAT_ATTEMPTED_V2 = "seat.attempted.v2"} 추가.</li>
 *     <li>이전 토픽 정리는 모든 consumer 이주를 확인한 후 deprecate 표시 → 다음 마이그레이션에서 삭제.</li>
 *   </ul>
 * </p>
 */
public final class Topics {

    private Topics() {
        // utility class
    }

    // ---- 좌석 (module-seat) -------------------------------------------------------
    /** 좌석 점유 시도(성공/실패 모두) 이벤트. */
    public static final String SEAT_ATTEMPTED = "seat.attempted.v1";

    /** 좌석 점유 성공(분산락 획득) 이벤트. */
    public static final String SEAT_HELD = "seat.held.v1";

    /** 좌석 해제(TTL 만료/명시 해제/결제 실패) 이벤트. */
    public static final String SEAT_RELEASED = "seat.released.v1";

    /** 좌석 판매 확정(결제 성공) 이벤트. */
    public static final String SEAT_SOLD = "seat.sold.v1";

    // ---- 결제 (module-payment-saga) -----------------------------------------------
    /** 결제 시작 명령 이벤트(Saga 진입). */
    public static final String PAYMENT_STARTED = "payment.started.v1";

    /** 결제 승인 이벤트. */
    public static final String PAYMENT_APPROVED = "payment.approved.v1";

    /** 결제 실패 이벤트. */
    public static final String PAYMENT_FAILED = "payment.failed.v1";

    /** 결제 환불 이벤트. */
    public static final String PAYMENT_REFUNDED = "payment.refunded.v1";

    // ---- 가격 (module-pricing) ----------------------------------------------------
    /** 1초 윈도우마다 산출되는 가격 틱 이벤트. */
    public static final String PRICING_TICK = "pricing.tick.v1";

    /** 사용자 수요 신호 (좌석 페이지 진입, 점유 시도 등). */
    public static final String DEMAND_SIGNAL = "pricing.demand.v1";

    // ---- 알람 (module-alert) ------------------------------------------------------
    /** 알람 발송 결과 이벤트(성공/실패 추적용). */
    public static final String ALERT_SENT = "alert.sent.v1";

    // ---- 대기열 (module-queue) ----------------------------------------------------
    /** 대기열 진입/통과/이탈 이벤트. */
    public static final String QUEUE_EVENT = "queue.event.v1";

    /** 매크로 탐지 알림 이벤트. */
    public static final String MACRO_DETECTED = "queue.macro.v1";
}
