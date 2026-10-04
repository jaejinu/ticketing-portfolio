package com.ticketing.alert.dispatch;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 알람 채널별 정책 설정.
 *
 * <pre>
 *   app:
 *     alert:
 *       fcm:
 *         mock-url:       http://localhost:8086/send
 *         timeout:        PT3S
 *         quota-per-min:  600          # 분당 600건
 *       smtp:
 *         from:           noreply@ticketing.local
 *         quota-per-min:  120
 *       webhook:
 *         quota-per-min:  300
 * </pre>
 *
 * <h2>왜 채널별 quota 인가</h2>
 * <p>
 *   외부 채널은 통상 분당/시간당 호출 한도(Free tier / 비용 보호) 가 있다.
 *   서버가 분당 60만 알람을 발동시켜도 채널이 받아주지 못하면 의미 없음 — 토큰 버킷으로
 *   채널 quota 안에서 페이스 조절. 초과분은 {@link DispatchStatus#QUOTA_EXCEEDED} 로 격리.
 * </p>
 */
@ConfigurationProperties(prefix = "app.alert")
public class AlertChannelProperties {

    private final Fcm fcm = new Fcm();
    private final Smtp smtp = new Smtp();
    private final Webhook webhook = new Webhook();

    public Fcm getFcm() { return fcm; }
    public Smtp getSmtp() { return smtp; }
    public Webhook getWebhook() { return webhook; }

    public static class Fcm {
        private String mockUrl = "http://localhost:8086/send";
        private Duration timeout = Duration.ofSeconds(3);
        private int quotaPerMin = 600;

        public String getMockUrl() { return mockUrl; }
        public void setMockUrl(String mockUrl) { this.mockUrl = mockUrl; }
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
        public int getQuotaPerMin() { return quotaPerMin; }
        public void setQuotaPerMin(int quotaPerMin) { this.quotaPerMin = quotaPerMin; }
    }

    public static class Smtp {
        private String from = "noreply@ticketing.local";
        private int quotaPerMin = 120;

        public String getFrom() { return from; }
        public void setFrom(String from) { this.from = from; }
        public int getQuotaPerMin() { return quotaPerMin; }
        public void setQuotaPerMin(int quotaPerMin) { this.quotaPerMin = quotaPerMin; }
    }

    public static class Webhook {
        private int quotaPerMin = 300;

        public int getQuotaPerMin() { return quotaPerMin; }
        public void setQuotaPerMin(int quotaPerMin) { this.quotaPerMin = quotaPerMin; }
    }
}
