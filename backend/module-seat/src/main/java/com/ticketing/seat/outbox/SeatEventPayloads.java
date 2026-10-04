package com.ticketing.seat.outbox;

// SeatEventPayloads 만 본 패키지에 남는다 — 도메인 특화 페이로드 빌더.
// 공용 인프라(OutboxRecord/Writer/Publisher) 는 common-outbox 모듈로 이동됨.

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ticketing.seat.domain.SeatHold;

import java.util.UUID;

/**
 * SeatHold 도메인 → Envelope payload 변환 헬퍼.
 *
 * <h2>왜 별도 클래스인가</h2>
 * <p>
 *   서비스 코드가 ObjectMapper API 호출로 어지러워지는 것을 막고, payload 스키마 변경을
 *   한곳에서 관리하기 위함. consumer 쪽도 동일 schema 를 참조하므로 future-proof.
 * </p>
 *
 * <h2>스키마 버전</h2>
 * <ul>
 *   <li>SEAT_HELD v1, SEAT_RELEASED v1 — 본 Phase 의 첫 정의.</li>
 *   <li>호환성 깨는 변경 시 v2 메서드 추가 + Topics 의 새 상수 발행.</li>
 * </ul>
 */
public final class SeatEventPayloads {

    public static final int VERSION_V1 = 1;

    private SeatEventPayloads() {
    }

    /**
     * SEAT_HELD payload.
     * <pre>
     *   {
     *     "holdId": "uuid",
     *     "scheduleId": "uuid",
     *     "holderId": "uuid",
     *     "seatIds": ["uuid", ...],
     *     "expiresAt": "2026-06-21T11:23:44Z"
     *   }
     * </pre>
     */
    public static JsonNode seatHeld(ObjectMapper mapper, SeatHold hold) {
        ObjectNode node = mapper.createObjectNode();
        node.put("holdId", hold.getId().toString());
        node.put("scheduleId", hold.getScheduleId().toString());
        node.put("holderId", hold.getHolderId().toString());
        ArrayNode seats = node.putArray("seatIds");
        for (UUID s : hold.getSeatIds()) {
            seats.add(s.toString());
        }
        node.put("expiresAt", hold.getExpiresAt().toInstant().toString());
        return node;
    }

    /**
     * SEAT_RELEASED payload — reason 으로 사용자 명시/만료 구분.
     *
     * @param reason "user" (사용자 명시 release) | "expired" (TTL 만료 스케줄러)
     */
    public static JsonNode seatReleased(ObjectMapper mapper, SeatHold hold, String reason) {
        ObjectNode node = mapper.createObjectNode();
        node.put("holdId", hold.getId().toString());
        node.put("scheduleId", hold.getScheduleId().toString());
        node.put("holderId", hold.getHolderId().toString());
        ArrayNode seats = node.putArray("seatIds");
        for (UUID s : hold.getSeatIds()) {
            seats.add(s.toString());
        }
        node.put("reason", reason);
        return node;
    }

    /**
     * DEMAND_SIGNAL payload — 좌석 점유 시도 결과를 kafka streams 가격 집계용으로 발행.
     *
     * <pre>
     *   {
     *     "scheduleId": "uuid",
     *     "sectionId":  "uuid",
     *     "outcome":    "HELD" | "CONFLICT",
     *     "seatCount":  4
     *   }
     * </pre>
     *
     * <p>
     *   하나의 hold 가 여러 section 좌석을 잡을 수 있으므로 section 별로 분해해 N건 발행한다.
     *   pricing 모듈의 Kafka Streams 토폴로지가 (scheduleId, sectionId) 키로 1초 텀블링 집계.
     * </p>
     */
    public static JsonNode demandSignal(ObjectMapper mapper,
                                         java.util.UUID scheduleId,
                                         java.util.UUID sectionId,
                                         String outcome,
                                         int seatCount) {
        ObjectNode node = mapper.createObjectNode();
        node.put("scheduleId", scheduleId.toString());
        node.put("sectionId", sectionId.toString());
        node.put("outcome", outcome);
        node.put("seatCount", seatCount);
        return node;
    }

    /**
     * SEAT_SOLD payload — 결제 saga 가 hold 를 SOLD 로 확정한 시점에 발행.
     * <pre>
     *   {
     *     "holdId": "uuid",
     *     "scheduleId": "uuid",
     *     "holderId": "uuid",
     *     "seatIds": ["uuid", ...]
     *   }
     * </pre>
     */
    public static JsonNode seatSold(ObjectMapper mapper, SeatHold hold) {
        ObjectNode node = mapper.createObjectNode();
        node.put("holdId", hold.getId().toString());
        node.put("scheduleId", hold.getScheduleId().toString());
        node.put("holderId", hold.getHolderId().toString());
        ArrayNode seats = node.putArray("seatIds");
        for (UUID s : hold.getSeatIds()) {
            seats.add(s.toString());
        }
        return node;
    }
}
