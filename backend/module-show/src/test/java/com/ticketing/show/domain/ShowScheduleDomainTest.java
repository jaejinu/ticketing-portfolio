package com.ticketing.show.domain;

import com.ticketing.common.error.BusinessException;
import com.ticketing.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ShowSchedule 도메인 단위 테스트 — 상태 전이 가드.
 *
 * <p>
 *   R-NDXWEZ 회차 ON_SALE 전환 PR 의 핵심 — pricing/queue 스케줄러가 ON_SALE 회차만 보기 때문에
 *   전이 규칙이 정확해야 한다.
 * </p>
 */
class ShowScheduleDomainTest {

    @Test
    @DisplayName("markOnSale — SCHEDULED → ON_SALE")
    void markOnSale_fromScheduled() {
        ShowSchedule s = freshSchedule();
        s.markOnSale();
        assertThat(s.getStatus()).isEqualTo(ShowScheduleStatus.ON_SALE);
    }

    @Test
    @DisplayName("markOnSale 멱등 — ON_SALE 재호출 시 no-op")
    void markOnSale_idempotent() {
        ShowSchedule s = freshSchedule();
        s.markOnSale();
        s.markOnSale();
        assertThat(s.getStatus()).isEqualTo(ShowScheduleStatus.ON_SALE);
    }

    @Test
    @DisplayName("markOnSale — CLOSED 회차에는 ON_SALE 으로 못 돌아감")
    void markOnSale_fromClosed_rejected() {
        ShowSchedule s = freshSchedule();
        s.markClosed();
        assertThatThrownBy(s::markOnSale)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code())
                .isEqualTo(ErrorCode.INVALID_SHOW_STATE);
    }

    @Test
    @DisplayName("markSoldOut — ON_SALE → SOLD_OUT")
    void markSoldOut_fromOnSale() {
        ShowSchedule s = freshSchedule();
        s.markOnSale();
        s.markSoldOut();
        assertThat(s.getStatus()).isEqualTo(ShowScheduleStatus.SOLD_OUT);
    }

    @Test
    @DisplayName("markSoldOut — SCHEDULED 에서 호출 시 거부")
    void markSoldOut_fromScheduled_rejected() {
        ShowSchedule s = freshSchedule();
        assertThatThrownBy(s::markSoldOut)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code())
                .isEqualTo(ErrorCode.INVALID_SHOW_STATE);
    }

    @Test
    @DisplayName("markSoldOut 멱등")
    void markSoldOut_idempotent() {
        ShowSchedule s = freshSchedule();
        s.markOnSale();
        s.markSoldOut();
        s.markSoldOut();
        assertThat(s.getStatus()).isEqualTo(ShowScheduleStatus.SOLD_OUT);
    }

    @Test
    @DisplayName("markClosed — 어느 상태에서든 CLOSED")
    void markClosed_fromAnyState() {
        // SCHEDULED → CLOSED
        ShowSchedule a = freshSchedule();
        a.markClosed();
        assertThat(a.getStatus()).isEqualTo(ShowScheduleStatus.CLOSED);

        // ON_SALE → CLOSED
        ShowSchedule b = freshSchedule();
        b.markOnSale();
        b.markClosed();
        assertThat(b.getStatus()).isEqualTo(ShowScheduleStatus.CLOSED);

        // SOLD_OUT → CLOSED
        ShowSchedule c = freshSchedule();
        c.markOnSale();
        c.markSoldOut();
        c.markClosed();
        assertThat(c.getStatus()).isEqualTo(ShowScheduleStatus.CLOSED);
    }

    @Test
    @DisplayName("markClosed 멱등")
    void markClosed_idempotent() {
        ShowSchedule s = freshSchedule();
        s.markClosed();
        s.markClosed();
        assertThat(s.getStatus()).isEqualTo(ShowScheduleStatus.CLOSED);
    }

    // -------------------------------------------------------------------------

    private ShowSchedule freshSchedule() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return ShowSchedule.create(
                UUID.randomUUID(),
                now.plusDays(7),
                now.plusDays(7).plusHours(2),
                now.plusDays(3));
    }
}
