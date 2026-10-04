package com.ticketing.alert.dispatch;

/**
 * 채널별 알람 발송 추상.
 *
 * <p>
 *   구현체 3종 (FCM/SMTP/Webhook) 가 Spring 빈으로 등록되고,
 *   {@link AlertDispatchService} 가 {@code channel()} 매칭으로 dispatch.
 * </p>
 *
 * <h2>구현 가이드</h2>
 * <ul>
 *   <li>예외를 던지지 말고 {@link DispatchResult#failed(String)} 으로 변환 — consumer 가
 *       single record 단위로 처리하기 때문에 예외 누락이 fanout 자체를 멈출 수 있음.</li>
 *   <li>외부 호출 타임아웃을 채널별로 설정 — Phase 7b 기본 3초.</li>
 * </ul>
 */
public interface AlertChannelSender {

    /** AlertChannel 상수 ({@link com.ticketing.alert.domain.AlertChannel}). */
    String channel();

    DispatchResult send(DispatchContext ctx);
}
