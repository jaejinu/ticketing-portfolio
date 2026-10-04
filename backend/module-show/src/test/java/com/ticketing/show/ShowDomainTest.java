package com.ticketing.show;

import com.ticketing.common.error.BusinessException;
import com.ticketing.show.domain.Section;
import com.ticketing.show.domain.SectionGrade;
import com.ticketing.show.domain.Show;
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

        assertThatThrownBy(() -> section.changeBasePrice(120_000L, salesStart, now))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("판매 오픈 이후");

        assertThat(section.getBasePrice()).isEqualTo(100_000L);
    }

    @Test
    void section_base_price_change_allowed_before_sales_start() {
        Section section = Section.create(UUID.randomUUID(), "VIP", SectionGrade.VIP, 100_000L);
        OffsetDateTime salesStart = OffsetDateTime.parse("2026-01-10T00:00:00Z");
        OffsetDateTime now        = OffsetDateTime.parse("2026-01-01T00:00:00Z");  // 판매 오픈 이전

        section.changeBasePrice(150_000L, salesStart, now);

        assertThat(section.getBasePrice()).isEqualTo(150_000L);
    }
}
