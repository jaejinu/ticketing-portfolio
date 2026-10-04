package com.ticketing.pricing.api;

import com.ticketing.pricing.api.dto.PricingCandleResponse;
import com.ticketing.pricing.api.dto.PricingTickResponse;
import com.ticketing.pricing.application.PricingCandleService;
import com.ticketing.pricing.domain.PricingTickRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 공개 가격 조회 API.
 *
 * <h2>매핑</h2>
 * <pre>
 *   GET /api/v1/shows/{showId}/schedules/{scheduleId}/pricing
 *       — 회차의 모든 구역 최근 가격 (구역별 최신 1건)
 *   GET /api/v1/sections/{sectionId}/pricing/history?limit=60
 *       — 시계열 차트 raw 틱 (최근 N건)
 *   GET /api/v1/sections/{sectionId}/pricing/candles?interval=1m&limit=200  (Phase 5c)
 *       — OHLC 캔들 (TimescaleDB CAgg 기반, 없으면 raw 즉시 집계로 fallback)
 * </pre>
 *
 * <h2>인증</h2>
 * <p>
 *   SecurityConfig 가 {@code /api/v1/shows/**} GET 를 permitAll 했으므로 비로그인도 접근 가능.
 *   sections/* 경로는 별도 인증 정책 필요 — Phase 5b 에서 정리.
 * </p>
 */
@RestController
@RequestMapping("/api/v1")
public class PublicPricingController {

    private final PricingTickRepository repository;
    private final PricingCandleService candleService;

    public PublicPricingController(PricingTickRepository repository,
                                    PricingCandleService candleService) {
        this.repository = repository;
        this.candleService = candleService;
    }

    @GetMapping("/shows/{showId}/schedules/{scheduleId}/pricing")
    public ResponseEntity<Map<String, Object>> latestByScheduleId(
            @PathVariable UUID showId, @PathVariable UUID scheduleId) {
        List<PricingTickResponse> body = repository.findLatestByScheduleId(scheduleId).stream()
                .map(PricingTickResponse::of)
                .toList();
        return ResponseEntity.ok(Map.of("ticks", body));
    }

    @GetMapping("/sections/{sectionId}/pricing/history")
    public ResponseEntity<Map<String, Object>> history(
            @PathVariable UUID sectionId,
            @RequestParam(defaultValue = "60") int limit) {
        // 차트용 최근 N건 raw 틱 — 시간 역순. 캔들이 아닌 원본 신호가 필요할 때(예: 디버깅 뷰).
        List<PricingTickResponse> body = repository
                .findBySectionIdOrderByOccurredAtDesc(sectionId, PageRequest.of(0, Math.min(limit, 600)))
                .stream()
                .map(PricingTickResponse::of)
                .toList();
        return ResponseEntity.ok(Map.of("ticks", body));
    }

    /**
     * OHLC 캔들 조회 (Phase 5c).
     *
     * <p>
     *   {@code interval} 은 {@code 1m|1h|1d} 중 하나. limit 은 기본 60, 최대 500.
     *   TimescaleDB 확장이 있는 환경에서는 Continuous Aggregate 뷰에서 즉시 반환,
     *   없는 환경(테스트 컨테이너 등) 에서는 raw {@code pricing_ticks} 를 date_trunc 로 집계.
     * </p>
     */
    @GetMapping("/sections/{sectionId}/pricing/candles")
    public ResponseEntity<Map<String, Object>> candles(
            @PathVariable UUID sectionId,
            @RequestParam(defaultValue = "1m") String interval,
            @RequestParam(required = false) Integer limit) {
        List<PricingCandleResponse> body = candleService.query(sectionId, interval, limit).stream()
                .map(PricingCandleResponse::of)
                .toList();
        return ResponseEntity.ok(Map.of(
                "sectionId", sectionId,
                "interval", interval,
                "candles", body));
    }
}
