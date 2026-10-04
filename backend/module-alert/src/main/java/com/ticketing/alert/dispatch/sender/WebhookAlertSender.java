package com.ticketing.alert.dispatch.sender;

import com.ticketing.alert.dispatch.AlertChannelSender;
import com.ticketing.alert.dispatch.DispatchContext;
import com.ticketing.alert.dispatch.DispatchResult;
import com.ticketing.alert.domain.AlertChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Webhook 채널 sender — 사용자 등록 URL 로 POST.
 *
 * <p>
 *   Phase 7b 단계에선 사용자별 webhook URL 등록 UI 가 없어 발송 대신 로그만 남긴다.
 *   Phase 8 에서 {@code user_webhook_endpoints} 테이블 + 사용자 등록 API 도입 후 실제 호출.
 * </p>
 *
 * <p>
 *   본 sender 가 {@link DispatchResult#skipped} 를 반환하는 케이스로 통계에서 명확히 구분 — 운영자가
 *   "webhook 사용량을 어떻게 늘릴까" 를 데이터로 판단 가능.
 * </p>
 */
@Component
public class WebhookAlertSender implements AlertChannelSender {

    private static final Logger log = LoggerFactory.getLogger(WebhookAlertSender.class);

    @Override
    public String channel() {
        return AlertChannel.WEBHOOK;
    }

    @Override
    public DispatchResult send(DispatchContext ctx) {
        // TODO Phase 8: 사용자 등록 webhook URL 조회 후 RestClient POST.
        log.info("[webhook stub] alertId={} userId={} observed={}원",
                ctx.alertId(), ctx.userId(), ctx.observedPrice());
        return DispatchResult.skipped("webhook URL 미등록 (Phase 8)");
    }
}
