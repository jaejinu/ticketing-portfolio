package com.ticketing.seat.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.common.error.BusinessException;
import com.ticketing.common.error.ErrorCode;
import com.ticketing.events.Topics;
import com.ticketing.seat.domain.SeatHold;
import com.ticketing.seat.domain.SeatHoldRepository;
import com.ticketing.seat.lock.SeatLockHandle;
import com.ticketing.seat.lock.SeatLockManager;
import com.ticketing.seat.lock.SeatLockUnavailableException;
import com.ticketing.outbox.OutboxWriter;
import com.ticketing.seat.outbox.SeatEventPayloads;
import com.ticketing.show.domain.Seat;
import com.ticketing.show.domain.SeatRepository;
import com.ticketing.show.domain.SeatStatus;
import com.ticketing.show.domain.Section;
import com.ticketing.show.domain.SectionRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 좌석 점유/해제 애플리케이션 서비스.
 *
 * <h2>흐름 (점유)</h2>
 * <pre>
 *   1) 입력 검증 (seatIds 비어있지 않음, 중복 없음, 최대 N 이내)
 *   2) Seat 조회 (모두 같은 회차 소속이어야 함)
 *   3) Redisson tryLock ASC 순차 획득 (실패 시 보상 해제 → SEAT_ALREADY_HELD)
 *   4) Seat 도메인 메서드 hold() 호출 (AVAILABLE → HELD)
 *      └─ DB 충돌 시 잡힌 락 해제 후 예외 전파
 *   5) 가격 스냅샷 (구역별 최신 가격 틱, 없으면 basePrice) — 결제 금액 고정
 *   6) SeatHold 영속 (status=ACTIVE, expires_at=now+TTL, 스냅샷 포함)
 *   7) 응답 반환 (락은 TTL 동안 살아 있음)
 * </pre>
 *
 * <h2>흐름 (해제)</h2>
 * <pre>
 *   1) SeatHold 조회 (없으면 404)
 *   2) holder 검증 (본인 아니면 403)
 *   3) SeatHold.release() (status → RELEASED)
 *   4) Seat.release() 호출 (HELD → AVAILABLE)
 *   5) Redis 락 명시 해제 (없어도 멱등 — 이미 TTL 만료된 경우 그냥 통과)
 * </pre>
 *
 * <h2>트랜잭션 경계</h2>
 * <ul>
 *   <li>점유: 락 획득 → DB 트랜잭션 시작 → 영속. DB 실패 시 락 보상 해제.</li>
 *   <li>해제: DB 트랜잭션 안에서 SeatHold/Seat 상태 갱신 → 커밋 후 Redis 락 해제.
 *       Redis 가 실패해도 DB 상태가 truth 라 정합 회복은 만료 스케줄러가 책임.</li>
 * </ul>
 */
@Service
public class SeatHoldService {

    private static final Logger log = LoggerFactory.getLogger(SeatHoldService.class);

    /** 도메인 집합체 이름 — outbox aggregateType 으로 사용. */
    private static final String AGGREGATE_SEAT_HOLD = "SeatHold";

    private final SeatLockManager lockManager;
    private final SeatHoldRepository seatHoldRepository;
    private final SeatRepository seatRepository;
    private final SeatHoldProperties props;
    private final Clock clock;
    private final OutboxWriter outboxWriter;
    private final ObjectMapper objectMapper;
    private final SectionRepository sectionRepository;
    /**
     * 구역별 현재 가격 포트 — 구현은 module-pricing.
     * ObjectProvider 인 이유: pricing 없이 뜨는 컨텍스트(module-seat 단독 IT) 에서는 빈이 없고,
     * 그 경우 전 구역 base_price 로 폴백한다.
     */
    private final ObjectProvider<CurrentPriceProvider> currentPriceProvider;

    // ---- 메트릭 ---------------------------------------------------------------
    /** 점유 성공 카운트 — 좌석 페이지 트래픽 의미 있는 부분의 핵심 KPI. */
    private final Counter createdCounter;
    /** 좌석 충돌 카운트 — 다이나믹 프라이싱 수요 신호로도 활용 가능. */
    private final Counter conflictCounter;
    /** hold 의 생존 시간 (생성 → release/expire/sold). tag=termination. */
    private final Timer holdReleasedTimer;
    private final Timer holdExpiredTimer;

    public SeatHoldService(SeatLockManager lockManager,
                           SeatHoldRepository seatHoldRepository,
                           SeatRepository seatRepository,
                           SeatHoldProperties props,
                           Clock clock,
                           MeterRegistry meterRegistry,
                           OutboxWriter outboxWriter,
                           ObjectMapper objectMapper,
                           SectionRepository sectionRepository,
                           ObjectProvider<CurrentPriceProvider> currentPriceProvider) {
        this.lockManager = lockManager;
        this.seatHoldRepository = seatHoldRepository;
        this.seatRepository = seatRepository;
        this.props = props;
        this.clock = clock;
        this.outboxWriter = outboxWriter;
        this.objectMapper = objectMapper;
        this.sectionRepository = sectionRepository;
        this.currentPriceProvider = currentPriceProvider;

        this.createdCounter = Counter.builder("seat.holds.created")
                .description("Successful seat hold creations")
                .register(meterRegistry);
        this.conflictCounter = Counter.builder("seat.holds.conflicts")
                .description("Seat hold attempts rejected by SEAT_ALREADY_HELD")
                .register(meterRegistry);
        this.holdReleasedTimer = Timer.builder("seat.holds.lifetime")
                .description("Seat hold lifetime from creation to termination")
                .tag("termination", "released")
                .register(meterRegistry);
        this.holdExpiredTimer = Timer.builder("seat.holds.lifetime")
                .description("Seat hold lifetime from creation to termination")
                .tag("termination", "expired")
                .register(meterRegistry);

        // seat.holds.active — 현재 ACTIVE 상태인 hold 수. 폴링 시점에 DB count.
        // count 쿼리가 매 폴링마다 도므로 부하 우려가 있지만, 대상 row 가 작고 (< maxHolds)
        // expires_at partial index 가 작동하여 가볍다.
        Gauge.builder("seat.holds.active",
                seatHoldRepository,
                repo -> repo.countByStatus(com.ticketing.seat.domain.SeatHoldStatus.ACTIVE))
                .description("Number of currently active seat holds")
                .register(meterRegistry);
    }

    /**
     * 좌석 점유.
     *
     * @param scheduleId 회차 id (검증용)
     * @param holderId   점유 주체 사용자 id
     * @param seatIds    점유 요청 좌석 (1~maxSeats)
     * @return 영속된 SeatHold
     * @throws BusinessException SEAT_ALREADY_HELD / SEAT_NOT_FOUND / INVALID_REQUEST
     */
    @Transactional
    public SeatHold hold(UUID scheduleId, UUID holderId, List<UUID> seatIds) {
        // ---- 1) 입력 검증 ----------------------------------------------------
        if (seatIds == null || seatIds.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "좌석을 선택해 주세요.");
        }
        Set<UUID> dedup = new LinkedHashSet<>(seatIds);
        if (dedup.size() != seatIds.size()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "동일 좌석이 여러 번 포함되어 있습니다.");
        }
        if (dedup.size() > props.getMaxSeats()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "1회 점유 가능한 좌석은 " + props.getMaxSeats() + "석까지입니다.");
        }

        // ---- 2) Seat 도메인 조회 + 회차 검증 ---------------------------------
        // section_id 의 schedule 소속까지 보려면 join 이 필요한데, Phase 1 에선
        // SeatRepository 가 schedule 기반 조회를 이미 제공하므로 그것을 활용한다.
        // 단순화를 위해 우선 id 기반으로 조회하고, 각 Seat 의 section.show_schedule_id 가
        // 모두 scheduleId 와 일치하는지는 다음 PR(SeatRepository 확장) 에서 다룬다.
        // 본 단계에선 검증을 application 레이어에 두지 않고 도메인 hold() 가 던지는 충돌 예외만 본다.
        List<Seat> seats = seatRepository.findAllById(dedup);
        if (seats.size() != dedup.size()) {
            throw new BusinessException(ErrorCode.SEAT_NOT_FOUND,
                    "존재하지 않는 좌석 id 가 포함되어 있습니다.");
        }

        // ---- 3) 분산락 획득 (실패 시 매니저가 보상 해제까지 마침) -------------
        SeatLockHandle handle;
        try {
            handle = lockManager.tryAcquireAll(dedup.stream().toList(),
                    props.getLockWait(), props.getTtl());
        } catch (SeatLockUnavailableException ex) {
            // 다른 사용자가 점유 중. 비즈니스 의미 있는 충돌로 변환.
            conflictCounter.increment();
            throw new BusinessException(ErrorCode.SEAT_ALREADY_HELD,
                    "이미 다른 사용자가 점유한 좌석입니다. seatId=" + ex.failedSeatId());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.SEAT_LOCK_TIMEOUT,
                    "좌석 점유 요청이 인터럽트되었습니다.");
        }

        // ---- 4) Seat 도메인 hold() — DB 상태 전이 -----------------------------
        // 락은 잡혔지만 DB 의 seats.status 가 이미 HELD/SOLD 일 가능성은:
        //   - Redis 락 TTL 만료 후 만료 스케줄러가 아직 안 돌았거나(현재 미구현)
        //   - SOLD 후 본 hold 가 새로 들어왔거나
        // 둘 다 상태 충돌이므로 도메인 메서드가 BusinessException(SEAT_ALREADY_HELD) 던짐.
        try {
            for (Seat seat : seats) {
                seat.hold();
            }
            // ---- 5) 가격 스냅샷 — 이 순간의 구역별 현재가로 결제 금액 고정 ----------
            // 락을 잡은 뒤 같은 TX 안에서 조회. 이후 틱이 움직여도 이 hold 의 금액은 불변.
            HoldPriceSnapshot snapshot = snapshotPrices(scheduleId, seats);

            // ---- 6) SeatHold 영속 --------------------------------------------
            OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
            OffsetDateTime expiresAt = now.plus(props.getTtl());
            SeatHold hold = SeatHold.create(scheduleId, holderId,
                    handle.seatIds(), snapshot.sectionPrices(), snapshot.totalAmount(),
                    now, expiresAt);
            seatHoldRepository.save(hold);
            // outbox stage — 같은 트랜잭션 안에서 INSERT 되므로 dual write 문제 없음.
            outboxWriter.stage(AGGREGATE_SEAT_HOLD, hold.getId(),
                    "SEAT_HELD", Topics.SEAT_HELD,
                    SeatEventPayloads.seatHeld(objectMapper, hold),
                    SeatEventPayloads.VERSION_V1);

            // ---- Phase 5b: DEMAND_SIGNAL 분해 발행 -------------------------------
            // 동일 hold 가 여러 section 의 좌석을 잡았을 수 있어 section 별 카운트로 분해.
            // pricing 모듈의 Kafka Streams 토폴로지가 (scheduleId, sectionId) 1초 텀블링 집계.
            java.util.Map<UUID, Integer> bySection = new java.util.LinkedHashMap<>();
            for (Seat seat : seats) {
                bySection.merge(seat.getSectionId(), 1, Integer::sum);
            }
            for (var entry : bySection.entrySet()) {
                outboxWriter.stage(AGGREGATE_SEAT_HOLD, hold.getId(),
                        "DEMAND_SIGNAL", Topics.DEMAND_SIGNAL,
                        SeatEventPayloads.demandSignal(objectMapper,
                                scheduleId, entry.getKey(), "HELD", entry.getValue()),
                        SeatEventPayloads.VERSION_V1);
            }

            createdCounter.increment();
            log.debug("seat-hold created: holdId={}, holder={}, seats={}, expiresAt={}",
                    hold.getId(), holderId, hold.getSeatIds(), expiresAt);
            return hold;

        } catch (RuntimeException ex) {
            // DB 전이 실패 → 잡힌 락을 즉시 보상 해제.
            handle.releaseOnFailure();
            throw ex;
        }
    }

    /**
     * 좌석들의 구역별 현재 가격을 조회해 스냅샷 생성.
     * 가격 틱이 없는 구역(또는 pricing 모듈 부재) 은 Section.basePrice 로 폴백.
     */
    private HoldPriceSnapshot snapshotPrices(UUID scheduleId, List<Seat> seats) {
        List<UUID> sectionIds = seats.stream().map(Seat::getSectionId).distinct().toList();
        Map<UUID, Long> basePrices = new HashMap<>();
        for (Section section : sectionRepository.findAllById(sectionIds)) {
            basePrices.put(section.getId(), section.getBasePrice());
        }
        CurrentPriceProvider provider = currentPriceProvider.getIfAvailable();
        Map<UUID, Long> currentPrices = provider != null
                ? provider.currentPrices(scheduleId, sectionIds)
                : Map.of();
        return HoldPriceSnapshot.of(seats, basePrices, currentPrices);
    }

    /**
     * 본인 점유 단건 조회. 본인이 아니면 SHOW_ACCESS_DENIED.
     */
    @Transactional(readOnly = true)
    public SeatHold getMyHold(UUID holdId, UUID requesterId) {
        SeatHold hold = seatHoldRepository.findById(holdId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SEAT_NOT_FOUND,
                        "점유 정보를 찾을 수 없습니다. holdId=" + holdId));
        if (!hold.getHolderId().equals(requesterId)) {
            throw new BusinessException(ErrorCode.SHOW_ACCESS_DENIED,
                    "본인의 점유만 조회할 수 있습니다.");
        }
        return hold;
    }

    /**
     * 명시 해제.
     *
     * <p>
     *   해제는 사용자가 "선택 취소" 누른 경우. TTL 만료를 기다리지 않고 즉시 좌석을 풀어
     *   다른 사용자가 잡을 수 있게 한다.
     * </p>
     */
    @Transactional
    public SeatHold release(UUID holdId, UUID requesterId) {
        SeatHold hold = seatHoldRepository.findForUpdate(holdId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SEAT_NOT_FOUND,
                        "점유 정보를 찾을 수 없습니다. holdId=" + holdId));

        // 멱등 — 이미 RELEASED/EXPIRED/SOLD 인 hold 도 본인이면 200 OK 로 그대로 응답.
        boolean wasActive = hold.isActive();
        OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        hold.release(requesterId, now);

        if (wasActive) {
            // ---- Seat 상태 전이 (HELD → AVAILABLE) ---------------------------
            List<Seat> seats = seatRepository.findAllById(hold.getSeatIds());
            for (Seat seat : seats) {
                seat.release();
            }

            // ---- Redis 락 강제 해제 -------------------------------------------
            // tryLock 한 스레드와 release 호출 스레드가 다르므로 forceUnlock 의미로 호출.
            // holder 검증은 위 SeatHold.release() 가 이미 마쳤으므로 안전.
            lockManager.forceReleaseAll(new ArrayList<>(hold.getSeatIds()));
            // outbox stage — 사용자 명시 release.
            outboxWriter.stage(AGGREGATE_SEAT_HOLD, hold.getId(),
                    "SEAT_RELEASED", Topics.SEAT_RELEASED,
                    SeatEventPayloads.seatReleased(objectMapper, hold, "user"),
                    SeatEventPayloads.VERSION_V1);
            // hold 생명주기 timer 기록 (release 분기)
            Duration lifetime = Duration.between(hold.getCreatedAt(), now);
            holdReleasedTimer.record(lifetime.toNanos(), TimeUnit.NANOSECONDS);
            log.debug("seat-hold released by user: holdId={}, holder={}",
                    hold.getId(), requesterId);
        }
        return hold;
    }

    /** 본인이 잡은 hold 이력 (활성 + 종료). */
    @Transactional(readOnly = true)
    public List<SeatHold> listMine(UUID holderId) {
        return new ArrayList<>(seatHoldRepository.findByHolderIdOrderByCreatedAtDesc(holderId));
    }

    /**
     * 결제 saga 가 SOLD 확정할 때 호출.
     *
     * <h2>흐름</h2>
     * <ol>
     *   <li>hold ACTIVE 검증 (이미 EXPIRED / RELEASED / SOLD 면 BusinessException)</li>
     *   <li>holder 검증 (다른 사용자의 hold 를 결제할 수 없음)</li>
     *   <li>Seat.sell() 호출 — HELD → SOLD</li>
     *   <li>SeatHold.markSold() — ACTIVE → SOLD</li>
     *   <li>outbox SEAT_SOLD 발행 (다른 모듈 — alert/pricing — 가 소비)</li>
     *   <li>Redis 락 강제 해제 — SOLD 좌석은 재점유 불가하므로 락 의미 없음</li>
     * </ol>
     *
     * <h2>왜 service 의 책임인가</h2>
     * <p>
     *   외부 모듈(payment) 가 SeatHold/Seat 도메인 메서드를 직접 호출하면 도메인 일관성을
     *   보장하기 어렵다. 본 service 메서드가 단일 진입점으로 트랜잭션 + 락 해제 + 이벤트 발행을 묶는다.
     * </p>
     *
     * @param holdId      판매 확정할 hold
     * @param requesterId 결제 주체 — hold 의 holderId 와 일치해야 함
     * @return SOLD 전이 후 SeatHold
     */
    @Transactional
    public SeatHold markSold(UUID holdId, UUID requesterId) {
        SeatHold hold = seatHoldRepository.findForUpdate(holdId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SEAT_NOT_FOUND,
                        "점유 정보를 찾을 수 없습니다. holdId=" + holdId));
        if (!hold.getHolderId().equals(requesterId)) {
            throw new BusinessException(ErrorCode.SHOW_ACCESS_DENIED,
                    "본인의 점유만 결제할 수 있습니다.");
        }
        // hold 가 만료/해제됐을 가능성을 도메인 메서드가 검사 (PAYMENT_ALREADY_SETTLED 또는 거절).
        // markSold 는 ACTIVE 가 아니면 BusinessException 던진다.
        OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);

        // Seat 도메인 SOLD 전이.
        List<Seat> seats = seatRepository.findAllById(hold.getSeatIds());
        for (Seat seat : seats) {
            seat.sell();
        }

        hold.markSold(now);

        // outbox SEAT_SOLD — payment 와 같은 TX 안에 있어 dual-write 없음.
        outboxWriter.stage(AGGREGATE_SEAT_HOLD, hold.getId(),
                "SEAT_SOLD", Topics.SEAT_SOLD,
                SeatEventPayloads.seatSold(objectMapper, hold),
                SeatEventPayloads.VERSION_V1);

        // Redis 락 강제 해제 — SOLD 좌석은 재점유 불가하므로 락 의미 없음.
        lockManager.forceReleaseAll(new ArrayList<>(hold.getSeatIds()));
        log.debug("seat-hold sold: holdId={}, holder={}", hold.getId(), requesterId);
        return hold;
    }

    /**
     * 만료 스케줄러용 배치 처리.
     *
     * <p>
     *   expires_at &lt; now 인 ACTIVE hold 를 최대 {@code batchSize} 개 묶어 EXPIRED 로 전이.
     *   각 hold 의 좌석은 HELD → AVAILABLE, Redis 락도 강제 해제.
     * </p>
     *
     * <h2>왜 한 트랜잭션에 묶는가</h2>
     * <ul>
     *   <li>배치 사이즈가 크지 않아(기본 100) 단일 트랜잭션 비용 적음.</li>
     *   <li>중간 실패 시 모두 롤백되어 다음 사이클이 같은 hold 를 다시 시도 — 멱등.</li>
     * </ul>
     *
     * <h2>주의</h2>
     * <p>
     *   다중 인스턴스에서는 호출자인 {@code SeatHoldExpiryScheduler} 의 ShedLock
     *   ({@code seat-hold-expiry}) 이 한 인스턴스만 실행되게 보장한다.
     * </p>
     *
     * @param batchSize 1회 처리 상한
     * @return 이번 사이클에서 EXPIRED 로 전이된 hold 수
     */
    @Transactional
    public int expireDueHolds(int batchSize) {
        OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        List<SeatHold> candidates = seatHoldRepository.findActiveExpiredBefore(
                now, PageRequest.of(0, batchSize));
        if (candidates.isEmpty()) {
            return 0;
        }

        int expired = 0;
        for (SeatHold hold : candidates) {
            // 좌석 상태 복귀 — HELD 였던 좌석만 AVAILABLE 로 되돌린다.
            // SOLD 로 이미 진입한 좌석(결제 saga 가 먼저 처리한 경우) 은 건드리지 않는다.
            List<Seat> seats = seatRepository.findAllById(hold.getSeatIds());
            for (Seat seat : seats) {
                if (SeatStatus.HELD.equals(seat.getStatus())) {
                    seat.release();
                }
            }
            Duration lifetime = Duration.between(hold.getCreatedAt(), now);
            hold.expire(now);

            // outbox stage — TTL 만료. consumer 가 reason 으로 분기.
            outboxWriter.stage(AGGREGATE_SEAT_HOLD, hold.getId(),
                    "SEAT_RELEASED", Topics.SEAT_RELEASED,
                    SeatEventPayloads.seatReleased(objectMapper, hold, "expired"),
                    SeatEventPayloads.VERSION_V1);

            // Redis 락 강제 해제 — TTL 만료로 이미 풀렸을 수 있지만 안전 차원에 한 번 더.
            // forceUnlock 은 만료/미존재 락에 호출해도 false 만 돌려주고 예외 없음.
            lockManager.forceReleaseAll(new ArrayList<>(hold.getSeatIds()));
            holdExpiredTimer.record(lifetime.toNanos(), TimeUnit.NANOSECONDS);
            expired++;
        }
        log.debug("expired {} seat-hold(s) at {}", expired, now);
        return expired;
    }
}
