package com.ticketing.pricing.application;

import com.ticketing.common.error.BusinessException;
import com.ticketing.common.error.ErrorCode;
import com.ticketing.pricing.domain.CandleInterval;
import com.ticketing.pricing.domain.PricingCandle;
import com.ticketing.pricing.domain.PricingCandleRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 캔들 조회 서비스.
 *
 * <p>
 *   현재는 Repository 얇은 래퍼지만, 이 계층에 두는 이유:
 *   <ul>
 *     <li>추후 캐시(RCache) / 응답 재정렬 / 다중 구역 batching 이 붙을 자리.</li>
 *     <li>limit 상한 등 API 계약 방어를 컨트롤러 밖에 두어 중복 방지.</li>
 *   </ul>
 * </p>
 */
@Service
public class PricingCandleService {

    /**
     * 캔들 조회 요청의 최대 크기.
     *
     * <p>
     *   프론트 차트가 한 화면에 보여줄 수 있는 실질 상한. 이보다 크게 요청하면 서버 응답 크기가
     *   급격히 커지고 클라이언트 렌더링도 느려진다. 초과 시 상한으로 clamp.
     * </p>
     */
    public static final int MAX_LIMIT = 500;
    public static final int DEFAULT_LIMIT = 60;

    private final PricingCandleRepository repository;

    public PricingCandleService(PricingCandleRepository repository) {
        this.repository = repository;
    }

    /**
     * @param sectionId    구역 UUID
     * @param intervalCode API 표현 (`1m`, `1h`, `1d`)
     * @param limit        요청 개수 — 상한 초과 시 clamp, 0 이하는 INVALID_REQUEST
     * @return 최근순 캔들 리스트 (bucket_start DESC)
     */
    public List<PricingCandle> query(UUID sectionId, String intervalCode, Integer limit) {
        if (sectionId == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "sectionId 가 필요합니다.");
        }
        CandleInterval interval = CandleInterval.fromCode(intervalCode);
        int effectiveLimit = normalizeLimit(limit);
        return repository.findCandles(sectionId, interval, effectiveLimit);
    }

    private int normalizeLimit(Integer requested) {
        if (requested == null) return DEFAULT_LIMIT;
        if (requested <= 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "limit 은 1 이상이어야 합니다.");
        }
        return Math.min(requested, MAX_LIMIT);
    }
}
