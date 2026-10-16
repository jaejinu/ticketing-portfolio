package com.ticketing.seat;

import com.ticketing.auth.domain.User;
import com.ticketing.auth.domain.UserRepository;
import com.ticketing.common.error.BusinessException;
import com.ticketing.common.error.ErrorCode;
import com.ticketing.seat.application.QueueAdmissionVerifier;
import com.ticketing.seat.application.SeatHoldProperties;
import com.ticketing.seat.application.SeatHoldService;
import com.ticketing.seat.domain.SeatHold;
import com.ticketing.seat.domain.SeatHoldRepository;
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
import com.ticketing.show.domain.ShowScheduleStatus;
import com.ticketing.show.domain.ShowStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 좌석 점유 "자격" 검사 — 2026-10-13 QA 에서 실측된 우회 경로를 고정한다.
 *
 * <ul>
 *   <li>다른 회차 좌석을 섞어 기본가로 사기 (현재가 123,000 → 100,000 결제 승인)</li>
 *   <li>없는 회차 id 로 400 을 받으면서 좌석은 5분간 묶이는 유령 점유</li>
 *   <li>판매 전·비공개·종료 공연 구매</li>
 *   <li>요청을 나눠 1인 4석 한도 우회 (5번 × 4석 = 공연장 전체)</li>
 *   <li>대기열을 거치지 않고 좌석 API 직접 호출</li>
 * </ul>
 */
// 베이스가 classes 를 명시하므로 중첩 @TestConfiguration 은 자동 감지되지 않는다 — 직접 Import.
@Import(SeatHoldEligibilityIntegrationTest.AdmissionStub.class)
class SeatHoldEligibilityIntegrationTest extends SeatIntegrationTestBase {

    /** 테스트가 직접 입장시키는 사용자 집합. 기본은 비어 있음 = 아무도 입장 안 함. */
    static final Set<String> ADMITTED = ConcurrentHashMap.newKeySet();

    @TestConfiguration
    static class AdmissionStub {
        @Bean
        QueueAdmissionVerifier queueAdmissionVerifier() {
            return (scheduleId, userId) -> ADMITTED.contains(scheduleId + ":" + userId);
        }
    }

    @Autowired SeatHoldService seatHoldService;
    @Autowired SeatHoldProperties props;
    @Autowired SeatHoldRepository seatHoldRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired SectionRepository sectionRepository;
    @Autowired ShowScheduleRepository scheduleRepository;
    @Autowired ShowRepository showRepository;
    @Autowired UserRepository userRepository;
    @Autowired RedissonClient redissonClient;

    private UUID organizerId;
    private UUID holderA;
    private UUID holderB;
    private UUID onSaleSchedule;
    private List<UUID> onSaleSeats;

    @BeforeEach
    void cleanAndSeed() {
        seatHoldRepository.deleteAllInBatch();
        seatRepository.deleteAllInBatch();
        sectionRepository.deleteAllInBatch();
        scheduleRepository.deleteAllInBatch();
        showRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
        redissonClient.getKeys().deleteByPattern(RedissonSeatLockManager.LOCK_KEY_PREFIX + "*");
        ADMITTED.clear();
        props.setRequireQueueAdmission(false);

        String hash = "$2a$04$placeholder.hash.for.test.only.xxxxxxxxxxxxxxxxxxxxxxxx";
        User organizer = userRepository.save(User.newUser("organizer-elig@example.com", hash, "Organizer"));
        User a = userRepository.save(User.newUser("elig-a@example.com", hash, "A"));
        User b = userRepository.save(User.newUser("elig-b@example.com", hash, "B"));
        organizerId = organizer.getId();
        holderA = a.getId();
        holderB = b.getId();

        UUID published = show(ShowStatus.PUBLISHED);
        onSaleSchedule = schedule(published, ShowScheduleStatus.ON_SALE, -1);
        onSaleSeats = seats(onSaleSchedule, 8);
    }

    @Test
    @DisplayName("다른 회차의 좌석은 거절 — 아무것도 저장하지 않고 좌석도 묶지 않는다")
    void seatFromAnotherScheduleIsRejectedBeforeAnyWrite() {
        UUID other = schedule(show(ShowStatus.PUBLISHED), ShowScheduleStatus.ON_SALE, -1);
        UUID foreignSeat = seats(other, 1).get(0);

        assertCode(() -> seatHoldService.hold(onSaleSchedule, holderA, List.of(onSaleSeats.get(0), foreignSeat)),
                ErrorCode.INVALID_REQUEST);

        assertThat(seatHoldRepository.count()).isZero();
        assertThat(status(foreignSeat)).isEqualTo(SeatStatus.AVAILABLE);
        // 거절된 요청은 Redis 락도 남기지 않는다 — 다른 사용자가 바로 잡을 수 있어야 한다.
        assertThat(seatHoldService.hold(other, holderB, List.of(foreignSeat)).getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("없는 회차 id 는 404 — 유령 점유가 생기지 않는다")
    void unknownScheduleLeavesNoGhostHold() {
        assertCode(() -> seatHoldService.hold(UUID.randomUUID(), holderA, List.of(onSaleSeats.get(0))),
                ErrorCode.SCHEDULE_NOT_FOUND);

        assertThat(seatHoldRepository.count()).isZero();
        assertThat(status(onSaleSeats.get(0))).isEqualTo(SeatStatus.AVAILABLE);
    }

    @Test
    @DisplayName("판매 중(공연 PUBLISHED + 회차 ON_SALE) 이 아니면 SALES_NOT_OPEN")
    void onlyOnSaleSchedulesCanBeHeld() {
        UUID published = show(ShowStatus.PUBLISHED);
        List<UUID> notOnSale = List.of(
                schedule(published, ShowScheduleStatus.SCHEDULED, -1),
                schedule(published, ShowScheduleStatus.SCHEDULED, +1),
                schedule(published, ShowScheduleStatus.CLOSED, -1),
                schedule(show(ShowStatus.DRAFT), ShowScheduleStatus.ON_SALE, -1),
                schedule(show(ShowStatus.CLOSED), ShowScheduleStatus.ON_SALE, -1));

        for (UUID scheduleId : notOnSale) {
            UUID seat = seats(scheduleId, 1).get(0);
            assertCode(() -> seatHoldService.hold(scheduleId, holderA, List.of(seat)), ErrorCode.SALES_NOT_OPEN);
            assertThat(status(seat)).isEqualTo(SeatStatus.AVAILABLE);
        }
        assertThat(seatHoldRepository.count()).isZero();
    }

    @Test
    @DisplayName("조직자가 예정 시각보다 일찍 연 판매(ON_SALE + 판매 시작 미래)는 살 수 있다")
    void earlyOpenedSaleCanBeHeld() {
        UUID early = schedule(show(ShowStatus.PUBLISHED), ShowScheduleStatus.ON_SALE, +3);
        UUID seat = seats(early, 1).get(0);
        assertThat(seatHoldService.hold(early, holderA, List.of(seat)).getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("1인 한도는 요청을 나눠도 회차 합계로 적용된다")
    void perUserLimitSpansRequests() {
        SeatHold first = seatHoldService.hold(onSaleSchedule, holderA, onSaleSeats.subList(0, 3));

        assertCode(() -> seatHoldService.hold(onSaleSchedule, holderA, onSaleSeats.subList(3, 5)),
                ErrorCode.SEAT_LIMIT_EXCEEDED);
        // 한도 안이면 통과 (3 + 1 = 4).
        seatHoldService.hold(onSaleSchedule, holderA, onSaleSeats.subList(3, 4));
        // 다른 사용자는 별개.
        seatHoldService.hold(onSaleSchedule, holderB, onSaleSeats.subList(4, 6));

        // 해제하면 그만큼 다시 잡을 수 있다.
        seatHoldService.release(first.getId(), holderA);
        seatHoldService.hold(onSaleSchedule, holderA, onSaleSeats.subList(6, 8));
    }

    @Test
    @DisplayName("같은 사용자의 동시 요청이 한도를 함께 통과하지 못한다")
    void concurrentRequestsFromSameUserCannotBothPassLimit() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<SeatHold>> results = new ArrayList<>();
            for (List<UUID> seats : List.of(onSaleSeats.subList(0, 3), onSaleSeats.subList(3, 6))) {
                results.add(pool.submit(() -> {
                    start.await();
                    return seatHoldService.hold(onSaleSchedule, holderA, seats);
                }));
            }
            start.countDown();
            int ok = 0;
            int limited = 0;
            for (Future<SeatHold> f : results) {
                try {
                    f.get(10, TimeUnit.SECONDS);
                    ok++;
                } catch (java.util.concurrent.ExecutionException ex) {
                    assertThat(ex.getCause()).isInstanceOfSatisfying(BusinessException.class,
                            be -> assertThat(be.code()).isEqualTo(ErrorCode.SEAT_LIMIT_EXCEEDED));
                    limited++;
                }
            }
            assertThat(ok).isEqualTo(1);
            assertThat(limited).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("입장 검사가 켜져 있으면 대기열을 통과한 사용자만 점유")
    void queueAdmissionIsRequiredWhenEnabled() {
        props.setRequireQueueAdmission(true);
        try {
            assertCode(() -> seatHoldService.hold(onSaleSchedule, holderA, List.of(onSaleSeats.get(0))),
                    ErrorCode.QUEUE_REJECTED);
            assertThat(seatHoldRepository.count()).isZero();

            ADMITTED.add(onSaleSchedule + ":" + holderA);
            assertThat(seatHoldService.hold(onSaleSchedule, holderA, List.of(onSaleSeats.get(0))).getStatus())
                    .isEqualTo("ACTIVE");
            // 다른 회차 입장으로는 안 된다.
            assertCode(() -> seatHoldService.hold(onSaleSchedule, holderB, List.of(onSaleSeats.get(1))),
                    ErrorCode.QUEUE_REJECTED);
        } finally {
            props.setRequireQueueAdmission(false);
        }
    }

    // ---- fixtures -----------------------------------------------------------

    private UUID show(ShowStatus status) {
        return showRepository.save(Show.createWithId(UUID.randomUUID(), organizerId,
                "[IT] Eligibility", "IT Hall", null, null, status)).getId();
    }

    /** @param salesStartOffsetDays 판매 시작 시각 = 지금 + N일 (음수면 이미 시작) */
    private UUID schedule(UUID showId, ShowScheduleStatus status, int salesStartOffsetDays) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return scheduleRepository.save(ShowSchedule.createWithId(UUID.randomUUID(), showId,
                now.plusDays(7), now.plusDays(7).plusHours(2), now.plusDays(salesStartOffsetDays), status)).getId();
    }

    private List<UUID> seats(UUID scheduleId, int count) {
        Section section = sectionRepository.save(Section.create(scheduleId, "R", SectionGrade.R, 100_000L));
        List<Seat> created = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            created.add(Seat.create(section.getId(), "A", i));
        }
        return seatRepository.saveAll(created).stream().map(Seat::getId).toList();
    }

    private String status(UUID seatId) {
        return seatRepository.findById(seatId).orElseThrow().getStatus();
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.code()).isEqualTo(code));
    }
}
