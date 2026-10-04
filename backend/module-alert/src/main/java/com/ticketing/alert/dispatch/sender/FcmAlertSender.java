package com.ticketing.alert.dispatch.sender;

import com.ticketing.alert.dispatch.AlertChannelProperties;
import com.ticketing.alert.dispatch.AlertChannelSender;
import com.ticketing.alert.dispatch.DispatchContext;
import com.ticketing.alert.dispatch.DispatchResult;
import com.ticketing.alert.domain.AlertChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * FCM Mock 채널 sender.
 *
 * <p>
 *   {@code app.alert.fcm.mock-url} 로 HTTP POST. fcm-mock 컨테이너는 실제
 *   <b>FCM HTTP v1 API</b>(POST /v1/projects/{p}/messages:send, body {"message": {...}})
 *   를 흉내내므로 요청 형식도 v1 규격으로 보낸다 — 실 FCM 전환 시 인증(google-auth)만
 *   추가하면 payload 는 그대로 재사용된다.
 *   (2026-07-29 QA: 기존 구현이 자체 형식 {to,title,body} 를 /send 로 보내
 *   mock 과 계약 불일치 → 전 건 404 FAILED 였던 것을 v1 규격으로 수정)
 * </p>
 *
 * <pre>
 *   POST /v1/projects/{projectId}/messages:send
 *   { "message": { "token": "...", "notification": { "title", "body" }, "data": {...} } }
 * </pre>
 */
@Component
public class FcmAlertSender implements AlertChannelSender {

    private static final Logger log = LoggerFactory.getLogger(FcmAlertSender.class);

    private final RestClient restClient;

    public FcmAlertSender(AlertChannelProperties props) {
        this.restClient = RestClient.builder()
                .baseUrl(props.getFcm().getMockUrl())
                .build();
    }

    @Override
    public String channel() {
        return AlertChannel.FCM;
    }

    @Override
    public DispatchResult send(DispatchContext ctx) {
        if (ctx.userEmail() == null || ctx.userEmail().isBlank()) {
            return DispatchResult.skipped("user email 누락");
        }
        // FCM v1 규격. 데모에선 사용자 이메일을 디바이스 token 자리에 사용
        // (실서비스는 사용자별 FCM 등록 token 저장소에서 조회).
        Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("title", "가격 하락 알림");
        notification.put("body", String.format(
                "관심 구역의 가격이 %,d원으로 떨어졌습니다 (목표 %,d원)",
                ctx.observedPrice(), ctx.thresholdPrice()));
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("token", ctx.userEmail());
        message.put("notification", notification);
        // data 값은 FCM 규격상 전부 문자열이어야 한다.
        message.put("data", Map.of("alertId", ctx.alertId().toString()));
        Map<String, Object> body = Map.of("message", message);
        try {
            restClient.post()
                    .uri("")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return DispatchResult.sent();
        } catch (Exception ex) {
            log.warn("FCM 발송 실패 alertId={}: {}", ctx.alertId(), ex.toString());
            return DispatchResult.failed(ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }
}
