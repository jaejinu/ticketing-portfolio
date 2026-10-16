package com.ticketing.show;

import com.ticketing.common.error.BusinessException;
import com.ticketing.show.domain.Section;
import com.ticketing.show.domain.SectionGrade;
import com.ticketing.show.domain.Show;
import com.ticketing.show.domain.ShowSchedule;
import com.ticketing.show.domain.ShowScheduleStatus;
import com.ticketing.show.domain.ShowStatus;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 도메인 단위 테스트 — 인프라 없이 가벼운 검증.
 *
 * <h2>검증 항목</h2>
 * <ul>
 *   <li>Show DRAFT → PUBLISHED 전이</li>
 *   <li>sales_start_at 이후 base_price 변경 거부</li>
 *   <li>sales_start_at 이전 base_price 변경 허용</li>
 * </ul>
 */
class ShowDomainTest {

    @Test
    void show_publish_transitions_from_draft_to_published() {
        Show show = Show.create(UUID.randomUUID(), "공연 A", "장소 A", "설명", null);
        assertThat(show.getStatus()).isEqualTo(ShowStatus.DRAFT);

        show.publish();

        assertThat(show.getStatus()).isEqualTo(ShowStatus.PUBLISHED);
    }

    @Test
    void show_publish_is_idempotent_when_already_published() {
        Show show = Show.create(UUID.randomUUID(), "공연 A", "장소 A", null, null);
        show.publish();
        show.publish();  // 두 번 호출해도 예외 없이 멱등

        assertThat(show.getStatus()).isEqualTo(ShowStatus.PUBLISHED);
    }

    @Test
    void show_publish_rejected_when_closed() {
        Show show = Show.create(UUID.randomUUID(), "공연 A", "장소 A", null, null);
        show.close();

        assertThatThrownBy(show::publish)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("DRAFT");
    }

    @Test
    void section_base_price_change_rejected_after_sales_start() {
        Section section = Section.create(UUID.randomUUID(), "VIP", SectionGrade.VIP, 100_000L);
        OffsetDateTime salesStart = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        OffsetDateTime now        = OffsetDateTime.parse("2026-01-02T00:00:00Z");  // 판매 오픈 이후

        assertThatThrownBy(() -> section.changeBasePrice(120_000L, schedule(salesStart, ShowScheduleStatus.SCHEDULED), now))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("판매 오픈 이후");

        assertThat(section.getBasePrice()).isEqualTo(100_000L);
    }

    @Test
    void section_base_price_change_allowed_before_sales_start() {
        Section section = Section.create(UUID.randomUUID(), "VIP", SectionGrade.VIP, 100_000L);
        OffsetDateTime salesStart = OffsetDateTime.parse("2026-01-10T00:00:00Z");
        OffsetDateTime now        = OffsetDateTime.parse("2026-01-01T00:00:00Z");  // 판매 오픈 이전

        section.changeBasePrice(150_000L, schedule(salesStart, ShowScheduleStatus.SCHEDULED), now);

        assertThat(section.getBasePrice()).isEqualTo(150_000L);
    }

    @Test
    void base_price_locked_when_sales_opened_early() {
        // 예정 시각 전이라도 조직자가 판매를 열었으면(ON_SALE) 기준가는 잠긴다.
        Section section = Section.create(UUID.randomUUID(), "VIP", SectionGrade.VIP, 100_000L);
        OffsetDateTime now = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        ShowSchedule early = schedule(now.plusDays(9), ShowScheduleStatus.ON_SALE);

        assertThatThrownBy(() -> section.changeBasePrice(1_000L, early, now))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("판매 오픈 이후");
    }

    @Test
    void sales_start_cannot_be_moved_once_sales_started() {
        // 2026-10-13 QA: 판매 중 회차의 sales_start_at 을 미래로 옮겨 기준가 잠금을 풀고 150,000 → 1,000.
        OffsetDateTime now = OffsetDateTime.parse("2026-01-05T00:00:00Z");
        ShowSchedule onSale = schedule(OffsetDateTime.parse("2026-01-01T00:00:00Z"), ShowScheduleStatus.ON_SALE);

        assertThatThrownBy(() -> onSale.updateSchedule(null, null, OffsetDateTime.parse("2026-01-08T00:00:00Z"), now))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("판매 시작 시각");
        // 공연 시각 조정은 판매 중에도 가능.
        onSale.updateSchedule(onSale.getStartsAt().plusHours(1), onSale.getEndsAt().plusHours(1), null, now);
    }

    @Test
    void schedule_dates_are_validated() {
        OffsetDateTime start = OffsetDateTime.parse("2026-03-01T10:00:00Z");
        assertThatThrownBy(() -> ShowSchedule.create(UUID.randomUUID(), start, start.minusHours(1), start.minusDays(7)))
                .hasMessageContaining("종료 시각");
        assertThatThrownBy(() -> ShowSchedule.create(UUID.randomUUID(), start, start.plusHours(2), start.plusDays(1)))
                .hasMessageContaining("판매 시작");
    }

    @Test
    void base_price_has_an_upper_bound() {
        // Long 최대값이 가격 계산을 매초 넘침으로 멈췄다.
        assertThatThrownBy(() -> Section.create(UUID.randomUUID(), "R", SectionGrade.R, Long.MAX_VALUE))
                .isInstanceOf(BusinessException.class);
        Section.create(UUID.randomUUID(), "R", SectionGrade.R, Section.MAX_BASE_PRICE);
    }

    private static ShowSchedule schedule(OffsetDateTime salesStart, ShowScheduleStatus status) {
        return ShowSchedule.createWithId(UUID.randomUUID(), UUID.randomUUID(),
                salesStart.plusDays(10), salesStart.plusDays(10).plusHours(2), salesStart, status);
    }
}
