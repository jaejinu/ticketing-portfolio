package com.ticketing.pricing.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.events.Topics;
import com.ticketing.outbox.OutboxWriter;
import com.ticketing.pricing.domain.PricingTick;
import com.ticketing.pricing.domain.PricingTickRepository;
import com.ticketing.pricing.outbox.PricingEventPayloads;
import com.ticketing.pricing.streams.DemandSignalReader;
import com.ticketing.seat.domain.SeatHoldRepository;
import com.ticketing.seat.domain.SeatHoldStatus;
import com.ticketing.show.domain.SeatRepository;
import com.ticketing.show.domain.SeatStatus;
import com.ticketing.show.domain.Section;
import com.ticketing.show.domain.SectionRepository;
import com.ticketing.show.domain.ShowSchedule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * 회차 단위로 가격 틱을 산출/영속/outbox stage.
 *
 * <h2>흐름 (한 회차당)</h2>
 * <pre>
 *   for each section in schedule:
 *     availableCount = SeatRepo.count(section, AVAILABLE)
 *     heldCount      = SeatRepo.count(section, HELD)
 *     soldCount      = SeatRepo.count(section, SOLD)
 *     total          = available + held + sold
 *     activeHolds    = SeatHoldRepo.count(scheduleId, ACTIVE)   # 회차 전체
 *
 *     occupancyRatio = (held + sold) / total
 *     demandPressure = min(1, activeHolds / total)  # 회차 전체 활성 점유 / 구역 총 좌석
 *     currentPrice   = calculator.calculate(basePrice, occupancyRatio, demandPressure)
 *
 *     save PricingTick
 *     outbox stage PRICING_TICK
 * </pre>
 *
 * <h2>왜 회차 단위 TX 인가</h2>
 * <p>
 *   회차 한 번에 모든 구역(통상 4개) 처리 → 단일 TX 비용 적음. 중간 실패 시 모두 롤백되어
 *   다음 사이클이 다시 시도 (멱등). 구역별 TX 분리는 멱등 보장이 복잡해진다.
 * </p>
 */
@Service
public class PricingTickService {

    private static final Logger log = LoggerFactory.getLogger(PricingTickService.class);
    private static final String AGGREGATE_PRICING = "PricingTick";

    private final PricingCalculator calculator;
    private final PricingTickRepository pricingTickRepository;
    private final SectionRepository sectionRepository;
    private final SeatRepository seatRepository;
    private final SeatHoldRepository seatHoldRepository;
    private final OutboxWriter outboxWriter;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    /**
     * Phase 5b — Kafka Streams 1초 텀블링 demand 카운트.
     *
     * <p>
     *   nullable: streams 가 미가용/스타트업 중일 때 reader 가 0 반환 — fallback 으로
     *   기존 SeatHoldRepository.countByScheduleIdAndStatus(ACTIVE) 사용.
     * </p>
     */
    private final DemandSignalReader demandReader;

    public PricingTickService(PricingCalculator calculator,
                               PricingTickRepository pricingTickRepository,
                               SectionRepository sectionRepository,
                               SeatRepository seatRepository,
                               SeatHoldRepository seatHoldRepository,
                               OutboxWriter outboxWriter,
                               ObjectMapper objectMapper,
                               Clock clock,
                               DemandSignalReader demandReader) {
        this.calculator = calculator;
        this.pricingTickRepository = pricingTickRepository;
        this.sectionRepository = sectionRepository;
        this.seatRepository = seatRepository;
        this.seatHoldRepository = seatHoldRepository;
        this.outboxWriter = outboxWriter;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.demandReader = demandReader;
    }

    @Transactional
    public int tickSchedule(ShowSchedule schedule) {
        OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        long activeHolds = seatHoldRepository.countByScheduleIdAndStatus(
                schedule.getId(), SeatHoldStatus.ACTIVE);

        List<Section> sections = sectionRepository.findByShowScheduleIdOrderByBasePriceDesc(schedule.getId());
        int produced = 0;
        for (Section section : sections) {
            long avail = seatRepository.countBySectionIdAndStatus(section.getId(), SeatStatus.AVAILABLE);
            long held  = seatRepository.countBySectionIdAndStatus(section.getId(), SeatStatus.HELD);
            long sold  = seatRepository.countBySectionIdAndStatus(section.getId(), SeatStatus.SOLD);
            long total = avail + held + sold;
            if (total == 0) {
                // 좌석이 아직 등록되지 않은 구역 — 가격 산출 무의미.
                continue;
            }

            BigDecimal occupancyRatio = BigDecimal.valueOf(held + sold)
                    .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);

            // Phase 5b: Kafka Streams 1초 윈도우 demand 카운트 우선 사용. 0 이면 fallback.
            // streams demand = "최근 2초 안에 잡은 좌석 수 합" — 회차 전체 ACTIVE 보다 즉각적.
            long streamsDemand = demandReader.readRecentDemand(
                    schedule.getId(), section.getId(),
                    java.time.Duration.ofSeconds(2));
            long demandRaw = streamsDemand > 0 ? streamsDemand : activeHolds;
            BigDecimal demandPressure = BigDecimal.valueOf(demandRaw)
                    .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP)
                    .min(BigDecimal.ONE);

            long current = calculator.calculate(section.getBasePrice(), occupancyRatio, demandPressure);

            PricingTick tick = PricingTick.of(
                    schedule.getId(), section.getId(),
                    section.getBasePrice(), current,
                    (int) avail, (int) held, (int) sold,
                    occupancyRatio, demandPressure, now);
            pricingTickRepository.save(tick);

            outboxWriter.stage(AGGREGATE_PRICING, tick.getId(),
                    "PRICING_TICK", Topics.PRICING_TICK,
                    PricingEventPayloads.tick(objectMapper, tick),
                    PricingEventPayloads.VERSION_V1);
            produced++;
        }
        log.debug("pricing-ticks produced: schedule={}, count={}", schedule.getId(), produced);
        return produced;
    }
}
