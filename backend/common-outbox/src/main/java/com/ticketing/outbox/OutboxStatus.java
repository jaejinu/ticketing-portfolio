package com.ticketing.outbox;

import java.util.Set;

/**
 * Outbox record 상태 — short string 상수.
 *
 * <p>
 *   enum 대신 String 상수 — DB CHECK 제약과 1:1 매핑, 로그/직렬화 비용 0.
 * </p>
 */
public final class OutboxStatus {

    /** 트랜잭션 커밋 직후 stage 된 상태. Publisher 가 다음 사이클에 발행 시도. */
    public static final String PENDING = "PENDING";

    /** Kafka 발행 + ACK 수신 완료. published_at 기록. */
    public static final String PUBLISHED = "PUBLISHED";

    /** max-retries 도달 — 운영자가 살펴봐야 하는 격리 상태. */
    public static final String FAILED = "FAILED";

    public static final Set<String> ALL = Set.of(PENDING, PUBLISHED, FAILED);

    private OutboxStatus() {
    }

    public static boolean isValid(String s) {
        return s != null && ALL.contains(s);
    }
}
