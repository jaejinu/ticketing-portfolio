package com.ticketing.paymentsaga.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ticketing.paymentsaga.domain.Payment;

/**
 * 결제 도메인 → Envelope payload 변환 헬퍼.
 *
 * <h2>스키마 버전</h2>
 * <ul>
 *   <li>PAYMENT_APPROVED v1, PAYMENT_FAILED v1 — 본 Phase 의 첫 정의.</li>
 * </ul>
 */
public final class PaymentEventPayloads {

    public static final int VERSION_V1 = 1;

    private PaymentEventPayloads() {
    }

    /**
     * PAYMENT_APPROVED payload.
     * <pre>
     *   { "paymentId", "holdId", "holderId", "scheduleId", "amount", "pgTxnId" }
     * </pre>
     */
    public static JsonNode approved(ObjectMapper mapper, Payment p) {
        ObjectNode n = mapper.createObjectNode();
        n.put("paymentId", p.getId().toString());
        n.put("holdId", p.getHoldId().toString());
        n.put("holderId", p.getHolderId().toString());
        n.put("scheduleId", p.getScheduleId().toString());
        n.put("amount", p.getAmount());
        n.put("pgTxnId", p.getPgTxnId());
        return n;
    }

    /**
     * PAYMENT_FAILED payload.
     * <pre>
     *   { "paymentId", "holdId", "holderId", "scheduleId", "amount", "failCode", "failReason" }
     * </pre>
     */
    public static JsonNode failed(ObjectMapper mapper, Payment p) {
        ObjectNode n = mapper.createObjectNode();
        n.put("paymentId", p.getId().toString());
        n.put("holdId", p.getHoldId().toString());
        n.put("holderId", p.getHolderId().toString());
        n.put("scheduleId", p.getScheduleId().toString());
        n.put("amount", p.getAmount());
        n.put("failCode", p.getFailCode());
        n.put("failReason", p.getFailReason());
        return n;
    }
}
