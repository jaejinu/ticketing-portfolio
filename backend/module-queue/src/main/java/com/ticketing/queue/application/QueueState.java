package com.ticketing.queue.application;

import java.util.Set;

/**
 * 대기열 상태 — short string 상수.
 *
 * <p>
 *   {@link com.ticketing.queue.application.VirtualQueueService#status(java.util.UUID)} 가
 *   클라이언트에게 응답하는 값.
 * </p>
 */
public final class QueueState {

    /** 대기 중 (waiting 큐 안에 있음). position 이 0보다 크다. */
    public static final String WAITING = "WAITING";

    /** 입장 허용 (admission 토큰이 살아있음). 좌석 페이지 진입 가능. */
    public static final String ADMITTED = "ADMITTED";

    /** ticket 이 만료/제거됨 (admission TTL 지남 또는 waiting 큐에서 제거됨). 재 enqueue 필요. */
    public static final String EXPIRED = "EXPIRED";

    public static final Set<String> ALL = Set.of(WAITING, ADMITTED, EXPIRED);

    private QueueState() {
    }
}
