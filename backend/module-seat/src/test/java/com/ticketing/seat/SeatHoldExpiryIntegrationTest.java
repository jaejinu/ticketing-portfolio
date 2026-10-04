package com.ticketing.seat;

import com.ticketing.auth.domain.User;
import com.ticketing.auth.domain.UserRepository;
import com.ticketing.seat.application.SeatHoldProperties;
import com.ticketing.seat.application.SeatHoldService;
import com.ticketing.seat.domain.SeatHold;
import com.ticketing.seat.domain.SeatHoldRepository;
import com.ticketing.seat.domain.SeatHoldStatus;
import com.ticketing.seat.lock.RedissonSeatLockManager;
import com.ticketing.show.domain.Seat;
import com.ticketing.show.domain.SeatRepository;
import com.ticketing.show.domain.SeatStatus;
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

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 좌석 점유 TTL 만료 시나리오 통합 테스트.
 *
 * <h2>전제</h2>
 * <ul>
 *   <li>test profile 의 ttl = PT2S — 짧은 TTL 로 실시간 만료 관찰</li>
 *   <li>expiry.enabled = false — 스케줄러는 끄고 service.expireDueHolds() 를 직접 호출 (결정적 검증)</li>
 * </ul>
 *
 * <h2>검증</h2>
 * <ol>
 *   <li>A 가 좌석 점유 → 활성 ACTIVE</li>
 *   <li>2.5초 대기 (TTL=2s 지남)</li>
 *   <li>service.expireDueHolds(100) 호출 → 1건 반환</li>
 *   <li>DB hold.status = EXPIRED, seat.status = AVAILABLE</li>
 *   <li>Redis 락 해제 (forceUnlock 효과 — 실제로는 leaseTime 만료로 이미 풀렸을 가능성 큼)</li>
 *   <li>B 가 같은 좌석 점유 성공</li>
 * </ol>
 */
class SeatHoldExpiryIntegrationTest extends SeatIntegrationTestBase {

    @Autowired SeatHoldService seatHoldService;
    @Autowired SeatHoldRepository seatHoldRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired SectionRepository sectionRepository;
    @Autowired ShowScheduleRepository scheduleRepository;
    @Autowired ShowRepository showRepository;
    @Autowired UserRepository userRepository;
    @Autowired RedissonClient redissonClient;
    @Autowired SeatHoldProperties props;

    private UUID scheduleId;
    private UUID seatId;
    private UUID holderA;
    private UUID holderB;

    @BeforeEach
    void cleanAndSeed() {
        seatHoldRepository.deleteAllInBatch();
        seatRepository.deleteAllInBatch();
        sectionRepository.deleteAllInBatch();
        scheduleRepository.deleteAllInBatch();
        showRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
        redissonClient.getKeys().deleteByPattern(RedissonSeatLockManager.LOCK_KEY_PREFIX + "*");

        User organizer = User.newUser("organizer-expiry@example.com",
                "$2a$04$placeholder.hash.for.test.only.xxxxxxxxxxxxxxxxxxxxxxxx", "Expiry Organizer");
        User a = User.newUser("a-expiry@example.com",
                "$2a$04$placeholder.hash.for.test.only.xxxxxxxxxxxxxxxxxxxxxxxx", "A");
        User b = User.newUser("b-expiry@example.com",
                "$2a$04$placeholder.hash.for.test.only.xxxxxxxxxxxxxxxxxxxxxxxx", "B");
        userRepository.save(organizer);
        userRepository.save(a);
        userRepository.save(b);
        this.holderA = a.getId();
        this.holderB = b.getId();

        Show show = Show.createWithId(UUID.randomUUID(), organizer.getId(),
                "[IT] Expiry", "IT Hall", null, null, ShowStatus.PUBLISHED);
        showRepository.save(show);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        ShowSchedule schedule = ShowSchedule.create(show.getId(),
                now.plusDays(7), now.plusDays(7).plusHours(2), now.plusDays(3));
        scheduleRepository.save(schedule);
        this.scheduleId = schedule.getId();

        Section section = Section.create(schedule.getId(), "VIP", SectionGrade.VIP, 100_000L);
        sectionRepository.save(section);

        Seat seat = Seat.create(section.getId(), "A", 1);
        seatRepository.save(seat);
        this.seatId = seat.getId();
    }

    @Test
    @DisplayName("TTL 만료 → EXPIRED 전이 → 좌석 AVAILABLE 복귀 → 다른 사용자 재점유 가능")
    void expiresActiveHold_andAllowsReHold() throws Exception {
        // ---- 1) A 점유 -------------------------------------------------------
        SeatHold a = seatHoldService.hold(scheduleId, holderA, List.of(seatId));
        assertThat(a.getStatus()).isEqualTo(SeatHoldStatus.ACTIVE);
        assertThat(seatRepository.findById(seatId).orElseThrow().getStatus())
                .isEqualTo(SeatStatus.HELD);

        // ---- 2) TTL(2s) + 약간의 여유 ------------------------------------------
        Thread.sleep(props.getTtl().toMillis() + 500);

        // ---- 3) 만료 배치 호출 (스케줄러가 꺼져 있으므로 직접) -----------------
        int expired = seatHoldService.expireDueHolds(props.getExpiry().getBatchSize());
        assertThat(expired)
                .as("만료된 hold 가 정확히 1건 처리되어야 한다")
                .isEqualTo(1);

        // ---- 4) DB 상태 ------------------------------------------------------
        SeatHold reloaded = seatHoldRepository.findById(a.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(SeatHoldStatus.EXPIRED);
        assertThat(reloaded.getReleasedAt())
                .as("expire 시점이 releasedAt 에 기록되어야 한다")
                .isNotNull();
        assertThat(seatRepository.findById(seatId).orElseThrow().getStatus())
                .as("좌석이 AVAILABLE 로 복귀")
                .isEqualTo(SeatStatus.AVAILABLE);

        // ---- 5) Redis 락 해제 (leaseTime 만료 + forceUnlock 효과) --------------
        assertThat(redissonClient.getLock(RedissonSeatLockManager.LOCK_KEY_PREFIX + seatId).isLocked())
                .as("만료 처리 후 Redis 락은 풀려 있어야 한다")
                .isFalse();

        // ---- 6) 다른 사용자가 즉시 점유 가능 ------------------------------------
        SeatHold b = seatHoldService.hold(scheduleId, holderB, List.of(seatId));
        assertThat(b.getStatus()).isEqualTo(SeatHoldStatus.ACTIVE);
        assertThat(b.getHolderId()).isEqualTo(holderB);
    }

    @Test
    @DisplayName("만료 후보 없을 때 배치 호출 — 0건 반환, 부작용 없음")
    void noExpiry_returnsZero() {
        SeatHold a = seatHoldService.hold(scheduleId, holderA, List.of(seatId));

        // TTL 안 지났으므로 만료 대상 0건.
        int expired = seatHoldService.expireDueHolds(props.getExpiry().getBatchSize());
        assertThat(expired).isZero();

        // 원래 hold 는 그대로 ACTIVE.
        assertThat(seatHoldRepository.findById(a.getId()).orElseThrow().getStatus())
                .isEqualTo(SeatHoldStatus.ACTIVE);
    }
}
