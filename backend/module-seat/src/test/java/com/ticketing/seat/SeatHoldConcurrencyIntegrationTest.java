package com.ticketing.seat;

import com.ticketing.auth.domain.User;
import com.ticketing.auth.domain.UserRepository;
import com.ticketing.common.error.BusinessException;
import com.ticketing.common.error.ErrorCode;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 좌석 점유 분산락 핵심 동시성 검증.
 *
 * <h2>검증 대상</h2>
 * <ul>
 *   <li>단일 사용자 단일/다중 좌석 점유 happy path (DB + Redis 락 상태)</li>
 *   <li>동일 좌석을 두 사용자가 동시에 점유 요청 → 정확히 1건 성공, 1건 SEAT_ALREADY_HELD</li>
 *   <li>다중 좌석을 반대 순서로 동시에 요청해도 데드락 없이 1건만 성공 (ASC 정렬 효과)</li>
 *   <li>본인 명시 release → Redis 락도 해제되어 다른 사용자가 즉시 재점유 가능</li>
 *   <li>본인 아닌 사용자의 release 시도 → SHOW_ACCESS_DENIED</li>
 *   <li>본인 release 멱등 (두 번 호출해도 예외 없음)</li>
 * </ul>
 *
 * <h2>독립성</h2>
 * 각 @Test 메서드는 {@link #cleanAndSeed()} 가 DB 와 Redis 락을 모두 초기화한 뒤 새 좌석을 시드.
 * Testcontainers 의 reuse=true 와 함께 동작해도 테스트 간 간섭 없음.
 */
class SeatHoldConcurrencyIntegrationTest extends SeatIntegrationTestBase {

    @Autowired SeatHoldService seatHoldService;
    @Autowired SeatHoldRepository seatHoldRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired SectionRepository sectionRepository;
    @Autowired ShowScheduleRepository scheduleRepository;
    @Autowired ShowRepository showRepository;
    @Autowired UserRepository userRepository;
    @Autowired RedissonClient redissonClient;

    // 시드 핸들
    private UUID scheduleId;
    private List<UUID> seatIds;     // 정렬된 4 좌석
    private UUID holderA;
    private UUID holderB;

    @BeforeEach
    void cleanAndSeed() {
        // ---- 1) FK 역순으로 깨끗이 정리 ----------------------------------------
        seatHoldRepository.deleteAllInBatch();
        seatRepository.deleteAllInBatch();
        sectionRepository.deleteAllInBatch();
        scheduleRepository.deleteAllInBatch();
        showRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();

        // ---- 2) Redis 락 잔재 제거 ----------------------------------------------
        // 이전 테스트에서 leak 된 키가 본 테스트에 영향 주지 않도록 명시 정리.
        // 본 테스트는 짧은 ttl(test profile = 2s) 이라 거의 leak 없지만 안전 차원.
        redissonClient.getKeys().deleteByPattern(RedissonSeatLockManager.LOCK_KEY_PREFIX + "*");

        // ---- 3) organizer + 사용자 2명 ----------------------------------------
        User organizer = User.newUser("organizer-seat-it@example.com",
                "$2a$04$placeholder.hash.for.test.only.xxxxxxxxxxxxxxxxxxxxxxxx", "Seat IT Organizer");
        User userA = User.newUser("user-a@example.com",
                "$2a$04$placeholder.hash.for.test.only.xxxxxxxxxxxxxxxxxxxxxxxx", "User A");
        User userB = User.newUser("user-b@example.com",
                "$2a$04$placeholder.hash.for.test.only.xxxxxxxxxxxxxxxxxxxxxxxx", "User B");
        userRepository.save(organizer);
        userRepository.save(userA);
        userRepository.save(userB);
        this.holderA = userA.getId();
        this.holderB = userB.getId();

        // ---- 4) PUBLISHED Show + Schedule + Section + Seat 4개 -----------------
        Show show = Show.createWithId(UUID.randomUUID(), organizer.getId(),
                "[IT] Concurrency Test", "IT Hall", null, null, ShowStatus.PUBLISHED);
        showRepository.save(show);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        ShowSchedule schedule = ShowSchedule.create(show.getId(),
                now.plusDays(7), now.plusDays(7).plusHours(2), now.plusDays(3));
        scheduleRepository.save(schedule);
        this.scheduleId = schedule.getId();

        Section section = Section.create(schedule.getId(), "VIP", SectionGrade.VIP, 100_000L);
        sectionRepository.save(section);

        // 좌석 4개. id 가 UUID 라 정렬 순서를 미리 알 수 없으므로 생성 후 정렬해 저장.
        List<Seat> seats = new ArrayList<>(List.of(
                Seat.create(section.getId(), "A", 1),
                Seat.create(section.getId(), "A", 2),
                Seat.create(section.getId(), "A", 3),
                Seat.create(section.getId(), "A", 4)));
        seatRepository.saveAll(seats);
        // 테스트가 정렬된 seatId 를 다루도록 ASC 로 정렬해 보관.
        this.seatIds = seats.stream()
                .map(Seat::getId)
                .sorted()
                .toList();
    }

    // =========================================================================
    // Happy path
    // =========================================================================

    @Test
    @DisplayName("단일 좌석 점유 — DB SeatHold + Seat.status=HELD + Redis 락 활성")
    void holdsSingleSeat_marksDbAndLock() {
        UUID seat = seatIds.get(0);

        SeatHold hold = seatHoldService.hold(scheduleId, holderA, List.of(seat));

        // ---- DB 상태 ----------------------------------------------------------
        assertThat(hold.getStatus()).isEqualTo(SeatHoldStatus.ACTIVE);
        assertThat(hold.getSeatIds()).containsExactly(seat);
        assertThat(hold.getExpiresAt()).isAfter(hold.getCreatedAt());
        // module-show 의 seats.status 가 HELD 로 전이됐는지.
        Seat refreshed = seatRepository.findById(seat).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(SeatStatus.HELD);

        // ---- Redis 락 살아 있는지 ---------------------------------------------
        assertThat(redissonClient.getLock(RedissonSeatLockManager.LOCK_KEY_PREFIX + seat).isLocked())
                .as("좌석 락이 Redis 에 살아 있어야 한다")
                .isTrue();
    }

    @Test
    @DisplayName("다중 좌석 점유 — 모든 좌석 HELD + 4개 락 활성")
    void holdsMultipleSeats_allLocked() {
        SeatHold hold = seatHoldService.hold(scheduleId, holderA, seatIds);

        assertThat(hold.getSeatIds()).containsExactlyInAnyOrderElementsOf(seatIds);
        for (UUID seat : seatIds) {
            assertThat(seatRepository.findById(seat).orElseThrow().getStatus())
                    .as("seatId=%s 가 HELD 여야 한다", seat)
                    .isEqualTo(SeatStatus.HELD);
            assertThat(redissonClient.getLock(RedissonSeatLockManager.LOCK_KEY_PREFIX + seat).isLocked())
                    .isTrue();
        }
    }

    // =========================================================================
    // 동시성: 동일 좌석
    // =========================================================================

    @Test
    @DisplayName("동일 좌석 동시 점유 — 정확히 1명만 성공, 다른 1명은 SEAT_ALREADY_HELD")
    void concurrentSameSeat_onlyOneSucceeds() throws Exception {
        UUID seat = seatIds.get(0);

        // CountDownLatch 로 두 스레드의 시작 시점을 정렬해 락 경쟁을 최대화.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);   // 두 스레드가 준비됐음을 알리는 신호
        CountDownLatch start = new CountDownLatch(1);   // 동시에 출발하라는 호각
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();

        try {
            Future<?> fA = pool.submit(() -> attempt(holderA, List.of(seat), ready, start, successes, conflicts));
            Future<?> fB = pool.submit(() -> attempt(holderB, List.of(seat), ready, start, successes, conflicts));

            ready.await();   // 두 스레드 모두 출발 직전까지 도달
            start.countDown(); // 동시에 시작
            fA.get(5, TimeUnit.SECONDS);
            fB.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(successes.get()).as("성공 카운트는 정확히 1이어야 한다").isEqualTo(1);
        assertThat(conflicts.get()).as("충돌 카운트는 정확히 1이어야 한다").isEqualTo(1);

        // DB 좌석은 정확히 HELD 1건, 활성 hold 1건이어야 한다.
        assertThat(seatRepository.findById(seat).orElseThrow().getStatus()).isEqualTo(SeatStatus.HELD);
        long active = seatHoldRepository.findAll().stream()
                .filter(h -> SeatHoldStatus.ACTIVE.equals(h.getStatus()))
                .count();
        assertThat(active).isEqualTo(1);
    }

    @Test
    @DisplayName("다중 좌석 반대 순서 동시 점유 — 데드락 없이 1건만 성공 (ASC 정렬 효과)")
    void concurrentReversedOrder_noDeadlock() throws Exception {
        // A: ASC 순서로 요청, B: DESC 순서로 요청. 매니저가 둘 다 ASC 로 정렬하므로 데드락 0.
        List<UUID> aOrder = new ArrayList<>(seatIds);                  // ASC
        List<UUID> bOrder = new ArrayList<>(seatIds);
        Collections.reverse(bOrder);                                   // DESC

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();

        try {
            Future<?> fA = pool.submit(() -> attempt(holderA, aOrder, ready, start, successes, conflicts));
            Future<?> fB = pool.submit(() -> attempt(holderB, bOrder, ready, start, successes, conflicts));
            ready.await();
            start.countDown();
            // 데드락이 있다면 양쪽 모두 lock-wait(100ms) 이내에 실패해 양쪽 다 conflict 로 떨어진다.
            // 정상 동작이라면 한쪽이 모든 락 잡고 hold() 완료 → 다른 쪽은 SEAT_ALREADY_HELD.
            fA.get(5, TimeUnit.SECONDS);
            fB.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(successes.get())
                .as("정확히 1건만 성공 (다른 1건은 충돌)")
                .isEqualTo(1);
        assertThat(conflicts.get())
                .as("정확히 1건만 충돌")
                .isEqualTo(1);

        // 성공한 쪽의 좌석 4개 모두 HELD
        long held = seatIds.stream()
                .map(id -> seatRepository.findById(id).orElseThrow())
                .filter(s -> SeatStatus.HELD.equals(s.getStatus()))
                .count();
        assertThat(held).isEqualTo(seatIds.size());
    }

    // =========================================================================
    // release 흐름
    // =========================================================================

    @Test
    @DisplayName("본인 release → Redis 락 해제 → 다른 사용자 즉시 재점유 가능")
    void releaseByHolder_allowsImmediateReHold() {
        UUID seat = seatIds.get(0);

        // A 가 점유
        SeatHold first = seatHoldService.hold(scheduleId, holderA, List.of(seat));
        assertThat(first.getStatus()).isEqualTo(SeatHoldStatus.ACTIVE);

        // A 가 명시 해제
        SeatHold released = seatHoldService.release(first.getId(), holderA);
        assertThat(released.getStatus()).isEqualTo(SeatHoldStatus.RELEASED);
        assertThat(released.getReleasedAt()).isNotNull();

        // DB 좌석은 AVAILABLE 로 복귀
        assertThat(seatRepository.findById(seat).orElseThrow().getStatus())
                .isEqualTo(SeatStatus.AVAILABLE);
        // Redis 락은 forceUnlock 으로 해제됨
        assertThat(redissonClient.getLock(RedissonSeatLockManager.LOCK_KEY_PREFIX + seat).isLocked())
                .as("명시 release 후 Redis 락이 즉시 풀려야 한다")
                .isFalse();

        // B 가 같은 좌석 점유 — 충돌 없이 성공해야 함
        SeatHold second = seatHoldService.hold(scheduleId, holderB, List.of(seat));
        assertThat(second.getStatus()).isEqualTo(SeatHoldStatus.ACTIVE);
        assertThat(second.getHolderId()).isEqualTo(holderB);
    }

    @Test
    @DisplayName("본인 아닌 사용자가 release 시도 — SHOW_ACCESS_DENIED")
    void releaseByNonHolder_forbidden() {
        UUID seat = seatIds.get(0);
        SeatHold hold = seatHoldService.hold(scheduleId, holderA, List.of(seat));

        assertThatThrownBy(() -> seatHoldService.release(hold.getId(), holderB))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code())
                .isEqualTo(ErrorCode.SHOW_ACCESS_DENIED);

        // 상태 변화 없음 — 여전히 ACTIVE
        SeatHold stillActive = seatHoldRepository.findById(hold.getId()).orElseThrow();
        assertThat(stillActive.getStatus()).isEqualTo(SeatHoldStatus.ACTIVE);
    }

    @Test
    @DisplayName("본인 release 멱등 — 두 번 호출해도 예외 없음, 상태는 RELEASED 유지")
    void releaseIdempotent() {
        UUID seat = seatIds.get(0);
        SeatHold hold = seatHoldService.hold(scheduleId, holderA, List.of(seat));

        SeatHold once = seatHoldService.release(hold.getId(), holderA);
        SeatHold twice = seatHoldService.release(hold.getId(), holderA);

        assertThat(once.getStatus()).isEqualTo(SeatHoldStatus.RELEASED);
        assertThat(twice.getStatus()).isEqualTo(SeatHoldStatus.RELEASED);
    }

    // =========================================================================
    // helpers
    // =========================================================================

    /**
     * 두 스레드에서 동시에 호출되는 점유 시도.
     *
     * <p>
     *   ready 로 "준비됨" 신호를 보내고 start 를 await 해 동일 시점에 hold 진입.
     *   성공/충돌만 카운팅하고 그 외 예외는 그대로 throw — 테스트가 실패 원인을 묻혀버리지 않도록.
     * </p>
     */
    private void attempt(UUID holder, List<UUID> seats,
                         CountDownLatch ready, CountDownLatch start,
                         AtomicInteger successes, AtomicInteger conflicts) {
        try {
            ready.countDown();
            start.await();
            seatHoldService.hold(scheduleId, holder, seats);
            successes.incrementAndGet();
        } catch (BusinessException ex) {
            if (ex.code() == ErrorCode.SEAT_ALREADY_HELD) {
                conflicts.incrementAndGet();
            } else {
                throw ex;
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(ex);
        }
    }
}
