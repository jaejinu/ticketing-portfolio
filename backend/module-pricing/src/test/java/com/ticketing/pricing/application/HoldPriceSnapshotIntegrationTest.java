package com.ticketing.pricing.application;

import com.ticketing.auth.domain.User;
import com.ticketing.auth.domain.UserRepository;
import com.ticketing.pricing.PricingIntegrationTestBase;
import com.ticketing.seat.api.dto.SeatHoldResponse;
import com.ticketing.seat.api.dto.SectionBreakdownItem;
import com.ticketing.seat.application.SeatHoldService;
import com.ticketing.seat.application.SeatHoldViewService;
import com.ticketing.seat.domain.SeatHold;
import com.ticketing.seat.domain.SeatHoldRepository;
import com.ticketing.seat.lock.RedissonSeatLockManager;
import com.ticketing.show.domain.Seat;
import com.ticketing.show.domain.SeatRepository;
import com.ticketing.show.domain.Section;
import com.ticketing.show.domain.SectionGrade;
import com.ticketing.show.domain.SectionRepository;
import com.ticketing.show.domain.Show;
import com.ticketing.show.domain.ShowRepository;
import com.ticketing.show.domain.ShowSchedule;
import com.ticketing.show.domain.ShowScheduleRepository;
import com.ticketing.show.domain.ShowStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 점유 시점 가격 스냅샷 end-to-end — seat 의 포트가 pricing 구현(PricingTickPriceProvider) 으로
 * 연결되어 최신 가격 틱이 hold 금액으로 고정되는지 검증.
 *
 * <h2>시나리오</h2>
 * <ol>
 *   <li>틱 있음 → hold 총액 = 틱 current_price (basePrice 아님)</li>
 *   <li>hold 이후 틱이 올라가도 → 결제 기대 금액은 점유 시점 값 그대로</li>
 *   <li>틱 없음 → basePrice 폴백</li>
 * </ol>
 *
 * <p>
 *   test 프로필은 PricingScheduler 가 꺼져 있어(app.pricing.enabled=false) 틱은 직접 INSERT —
 *   시나리오가 결정적이다.
 * </p>
 */
class HoldPriceSnapshotIntegrationTest extends PricingIntegrationTestBase {

    private static final long BASE_PRICE = 100_000L;

    @Autowired SeatHoldService seatHoldService;
    @Autowired SeatHoldViewService seatHoldViewService;
    @Autowired SeatHoldRepository seatHoldRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired SectionRepository sectionRepository;
    @Autowired ShowScheduleRepository scheduleRepository;
    @Autowired ShowRepository showRepository;
    @Autowired UserRepository userRepository;
    @Autowired RedissonClient redissonClient;
    @Autowired JdbcTemplate jdbc;

    private UUID scheduleId;
    private UUID sectionId;
    private List<UUID> seatIds;
    private UUID holderId;

    @BeforeEach
    void cleanAndSeed() {
        // FK 역순 정리. seat_hold_items / seat_hold_prices 는 ON DELETE CASCADE.
        jdbc.update("DELETE FROM pricing_ticks");
        seatHoldRepository.deleteAllInBatch();
        seatRepository.deleteAllInBatch();
        sectionRepository.deleteAllInBatch();
        scheduleRepository.deleteAllInBatch();
        showRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
        redissonClient.getKeys().deleteByPattern(RedissonSeatLockManager.LOCK_KEY_PREFIX + "*");

        User organizer = User.newUser("organizer-snapshot-it@example.com",
                "$2a$04$placeholder.hash.for.test.only.xxxxxxxxxxxxxxxxxxxxxxxx", "Snapshot IT Organizer");
        User holder = User.newUser("holder-snapshot-it@example.com",
                "$2a$04$placeholder.hash.for.test.only.xxxxxxxxxxxxxxxxxxxxxxxx", "Snapshot IT Holder");
        userRepository.save(organizer);
        userRepository.save(holder);
        this.holderId = holder.getId();

        Show show = Show.createWithId(UUID.randomUUID(), organizer.getId(),
                "[IT] Price Snapshot", "IT Hall", null, null, ShowStatus.PUBLISHED);
        showRepository.save(show);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        ShowSchedule schedule = ShowSchedule.create(show.getId(),
                now.plusDays(7), now.plusDays(7).plusHours(2), now.plusDays(3));
        scheduleRepository.save(schedule);
        this.scheduleId = schedule.getId();

        Section section = Section.create(schedule.getId(), "VIP", SectionGrade.VIP, BASE_PRICE);
        sectionRepository.save(section);
        this.sectionId = section.getId();

        List<Seat> seats = List.of(
                Seat.create(section.getId(), "A", 1),
                Seat.create(section.getId(), "A", 2));
        seatRepository.saveAll(seats);
        this.seatIds = seats.stream().map(Seat::getId).toList();
    }

    @Test
    @DisplayName("최신 가격 틱이 있으면 hold 총액 = 틱 현재가 × 좌석 수 (basePrice 아님)")
    void hold_snapshotsLatestTickPrice() {
        insertTick(103_000L, Instant.now().minusSeconds(5));
        insertTick(106_000L, Instant.now().minusSeconds(1));   // 최신

        SeatHold hold = seatHoldService.hold(scheduleId, holderId, seatIds);

        SeatHold reloaded = seatHoldRepository.findById(hold.getId()).orElseThrow();
        assertThat(reloaded.getSectionPrices()).containsEntry(sectionId, 106_000L);
        assertThat(reloaded.getTotalAmount()).isEqualTo(212_000L);
        assertThat(seatHoldViewService.calculateExpectedAmount(reloaded)).isEqualTo(212_000L);
    }

    @Test
    @DisplayName("hold 이후 가격이 올라도 결제 기대 금액은 점유 시점 값 그대로")
    void priceMovesAfterHold_expectedAmountUnchanged() {
        insertTick(106_000L, Instant.now().minusSeconds(1));
        SeatHold hold = seatHoldService.hold(scheduleId, holderId, seatIds);

        // 점유 후 가격 급등.
        insertTick(150_000L, Instant.now());

        SeatHold reloaded = seatHoldRepository.findById(hold.getId()).orElseThrow();
        assertThat(seatHoldViewService.calculateExpectedAmount(reloaded)).isEqualTo(212_000L);

        SeatHoldResponse view = seatHoldViewService.enrich(reloaded);
        assertThat(view.totalAmount()).isEqualTo(212_000L);
        SectionBreakdownItem item = view.sectionBreakdown().get(0);
        assertThat(item.unitPrice()).isEqualTo(106_000L);
        assertThat(item.basePrice()).isEqualTo(BASE_PRICE);
        assertThat(item.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("가격 틱이 없으면 basePrice 폴백")
    void noTick_fallsBackToBasePrice() {
        SeatHold hold = seatHoldService.hold(scheduleId, holderId, seatIds.subList(0, 1));

        SeatHold reloaded = seatHoldRepository.findById(hold.getId()).orElseThrow();
        assertThat(reloaded.getSectionPrices()).containsEntry(sectionId, BASE_PRICE);
        assertThat(reloaded.getTotalAmount()).isEqualTo(BASE_PRICE);
    }

    @Test
    @DisplayName("다른 회차의 틱은 무시하고 basePrice 폴백")
    void tickOfOtherSchedule_ignored() {
        insertTick(UUID.randomUUID(), 180_000L, Instant.now().minusSeconds(1));

        SeatHold hold = seatHoldService.hold(scheduleId, holderId, seatIds.subList(0, 1));

        SeatHold reloaded = seatHoldRepository.findById(hold.getId()).orElseThrow();
        assertThat(reloaded.getTotalAmount()).isEqualTo(BASE_PRICE);
    }

    // -------------------------------------------------------------------------

    private void insertTick(long currentPrice, Instant at) {
        insertTick(scheduleId, currentPrice, at);
    }

    private void insertTick(UUID tickScheduleId, long currentPrice, Instant at) {
        jdbc.update("""
                INSERT INTO pricing_ticks (
                    id, schedule_id, section_id, base_price, current_price,
                    available_count, held_count, sold_count,
                    occupancy_ratio, demand_pressure, occurred_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), tickScheduleId, sectionId,
                BASE_PRICE, currentPrice,
                2, 0, 0,
                BigDecimal.ZERO, BigDecimal.ZERO,
                Timestamp.from(at));
    }
}
