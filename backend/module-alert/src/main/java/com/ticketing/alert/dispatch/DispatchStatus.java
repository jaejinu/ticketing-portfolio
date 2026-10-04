package com.ticketing.alert.dispatch;

import java.util.Set;

/**
 * 알람 발송 결과 상태.
 *
 * <ul>
 *   <li>SENT            — 외부 채널 (FCM/SMTP/Webhook) 정상 응답</li>
 *   <li>FAILED          — 외부 채널 실패 (HTTP 오류/타임아웃)</li>
 *   <li>QUOTA_EXCEEDED  — 채널별 토큰 버킷 한도 초과 — 재시도 없음 (스팸 보호)</li>
 *   <li>SKIPPED         — 발송 대상 사용자 정보 결손 (email 없음 등)</li>
 * </ul>
 */
public final class DispatchStatus {

    public static final String SENT = "SENT";
    public static final String FAILED = "FAILED";
    public static final String QUOTA_EXCEEDED = "QUOTA_EXCEEDED";
    public static final String SKIPPED = "SKIPPED";

    public static final Set<String> ALL = Set.of(SENT, FAILED, QUOTA_EXCEEDED, SKIPPED);

    private DispatchStatus() {
    }
}
