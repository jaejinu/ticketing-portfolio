package com.ticketing.alert.domain;

import java.util.Set;

/**
 * 알람 발송 채널.
 *
 * <p>본 PR(7a) 에서는 채널별 발송 실구현 없이 ALERT_SENT 이벤트만 발행. Phase 7b 에서 실제 발송.</p>
 */
public final class AlertChannel {

    public static final String FCM = "FCM";
    public static final String SMTP = "SMTP";
    public static final String WEBHOOK = "WEBHOOK";

    public static final Set<String> ALL = Set.of(FCM, SMTP, WEBHOOK);

    private AlertChannel() {
    }
}
