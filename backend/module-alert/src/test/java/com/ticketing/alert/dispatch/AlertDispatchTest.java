package com.ticketing.alert.dispatch;

import com.ticketing.alert.domain.AlertChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AlertDispatch + DispatchResult 도메인 단위 테스트.
 */
class AlertDispatchTest {

    @Test
    @DisplayName("AlertDispatch.of — 모든 필드가 채워지고 occurredAt 이 현재")
    void of_initializesFields() {
        UUID alertId = UUID.randomUUID();
        AlertDispatch d = AlertDispatch.of(alertId, AlertChannel.FCM, DispatchStatus.SENT, null);

        assertThat(d.getId()).isNotNull();
        assertThat(d.getAlertId()).isEqualTo(alertId);
        assertThat(d.getChannel()).isEqualTo(AlertChannel.FCM);
        assertThat(d.getStatus()).isEqualTo(DispatchStatus.SENT);
        assertThat(d.getError()).isNull();
        assertThat(d.getOccurredAt()).isNotNull();
    }

    @Test
    @DisplayName("DispatchResult.sent — status=SENT, error=null")
    void sent_factory() {
        DispatchResult r = DispatchResult.sent();
        assertThat(r.status()).isEqualTo(DispatchStatus.SENT);
        assertThat(r.error()).isNull();
    }

    @Test
    @DisplayName("DispatchResult.failed — status=FAILED + 에러 메시지 보존")
    void failed_factory() {
        DispatchResult r = DispatchResult.failed("connection timeout");
        assertThat(r.status()).isEqualTo(DispatchStatus.FAILED);
        assertThat(r.error()).isEqualTo("connection timeout");
    }

    @Test
    @DisplayName("DispatchResult.quotaExceeded — status=QUOTA_EXCEEDED")
    void quotaExceeded_factory() {
        DispatchResult r = DispatchResult.quotaExceeded();
        assertThat(r.status()).isEqualTo(DispatchStatus.QUOTA_EXCEEDED);
        assertThat(r.error()).isNotBlank();
    }

    @Test
    @DisplayName("DispatchResult.skipped — status=SKIPPED + reason")
    void skipped_factory() {
        DispatchResult r = DispatchResult.skipped("user email 누락");
        assertThat(r.status()).isEqualTo(DispatchStatus.SKIPPED);
        assertThat(r.error()).isEqualTo("user email 누락");
    }
}
