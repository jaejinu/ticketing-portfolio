package com.ticketing.events;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 모든 도메인 이벤트의 공통 봉투(Envelope).
 *
 * <p>
 *   페이로드(payload)는 {@link JsonNode} 로 두어 모듈별 도메인 객체에 직접 의존하지 않는다.
 *   덕분에 common-events 모듈은 변경 빈도가 매우 낮게 유지된다.
 * </p>
 *
 * <h2>필드 의미</h2>
 * <ul>
 *   <li>{@code eventId}    — 이벤트 고유 UUID. 멱등 컨슈머가 중복 제거 키로 사용.</li>
 *   <li>{@code traceId}    — 분산 추적 ID(OTel 호환). 발신/수신 양쪽에서 span 연결.</li>
 *   <li>{@code occurredAt} — 이벤트 발생 시각(UTC Instant). 시계열 저장의 기준 시간.</li>
 *   <li>{@code type}       — 이벤트 타입 식별자. 예: "seat.attempted". 버전 없는 short name.</li>
 *   <li>{@code version}    — 페이로드 스키마 버전 (v1=1, v2=2). 토픽명 v<n> 과 일치.</li>
 *   <li>{@code payload}    — 실제 이벤트 페이로드. 모듈별 schema 가 정의(common-events 는 모름).</li>
 * </ul>
 *
 * <p>
 *   <b>호환성 정책:</b> 필드 추가는 가능. 필드 제거/타입 변경은 메이저 버전 분리(v2) 필수.
 *   필드를 추가하면 모든 consumer 가 즉시 재배포되지 않도록 default 값을 항상 부여한다.
 * </p>
 */
public record Envelope(
        UUID eventId,
        String traceId,
        Instant occurredAt,
        String type,
        int version,
        JsonNode payload) {

    public Envelope {
        Objects.requireNonNull(eventId,    "eventId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(type,       "type");
        Objects.requireNonNull(payload,    "payload");
        if (version < 1) {
            throw new IllegalArgumentException("version must be >= 1, actual=" + version);
        }
    }

    /**
     * 새 이벤트 생성용 헬퍼. eventId 는 UUID v7 권장이지만 의존성을 늘리지 않기 위해 v4 사용.
     * 추후 UUID v7 도입 시 단일 지점에서 교체 가능하도록 정적 팩토리로 캡슐화.
     */
    public static Envelope of(String type, int version, JsonNode payload, String traceId, Instant now) {
        return new Envelope(UUID.randomUUID(), traceId, now, type, version, payload);
    }
}
