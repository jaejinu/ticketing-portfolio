package com.ticketing.paymentsaga.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 결제 saga 설정.
 *
 * <pre>
 *   app:
 *     payment:
 *       mock-pg-base-url: http://localhost:8087
 *       mock-pg-timeout:  PT3S
 *       fail-on-pg-error: true   # PG 호출 자체 실패 시 결제를 FAILED 로 (재시도 X)
 * </pre>
 */
@ConfigurationProperties(prefix = "app.payment")
public class PaymentProperties {

    /** Mock PG 서버 base URL. infra/mock-services/mock-pg 가 8087 에서 응답. */
    private String mockPgBaseUrl = "http://localhost:8087";

    /** Mock PG HTTP 호출 timeout. 길어지면 사용자 체감 결제 지연. */
    private Duration mockPgTimeout = Duration.ofSeconds(3);

    /**
     * PG 호출 자체가 IOException/timeout 으로 실패했을 때의 정책.
     *   - true  : 결제를 FAILED 로 기록하고 사용자에게 즉시 응답 (이번 PR 기본)
     *   - false : PENDING 상태로 두고 별도 reconciler 가 PG 조회 후 결정 (Phase 5b)
     */
    private boolean failOnPgError = true;

    public String getMockPgBaseUrl() { return mockPgBaseUrl; }
    public void setMockPgBaseUrl(String mockPgBaseUrl) { this.mockPgBaseUrl = mockPgBaseUrl; }

    public Duration getMockPgTimeout() { return mockPgTimeout; }
    public void setMockPgTimeout(Duration mockPgTimeout) { this.mockPgTimeout = mockPgTimeout; }

    public boolean isFailOnPgError() { return failOnPgError; }
    public void setFailOnPgError(boolean failOnPgError) { this.failOnPgError = failOnPgError; }
}
