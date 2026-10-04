package com.ticketing.pricing.streams;

import java.util.UUID;

/**
 * 수요 집계 키 — (scheduleId, sectionId).
 *
 * <p>
 *   Kafka Streams 의 groupBy 후 키 타입으로 사용. 직렬화는 JsonSerde 또는 String key 로 단순화.
 *   본 토폴로지는 키를 {@code "{scheduleId}|{sectionId}"} 문자열로 변환해 Stream API 의
 *   기본 String Serde 만 사용 — 별도 Avro/JsonSerde 구성을 피한다.
 * </p>
 */
public record DemandKey(UUID scheduleId, UUID sectionId) {

    public String asString() {
        return scheduleId.toString() + "|" + sectionId.toString();
    }

    public static DemandKey parse(String key) {
        int sep = key.indexOf('|');
        if (sep < 0) {
            throw new IllegalArgumentException("invalid DemandKey: " + key);
        }
        return new DemandKey(
                UUID.fromString(key.substring(0, sep)),
                UUID.fromString(key.substring(sep + 1)));
    }
}
