package com.ticketing.queue.application.macro;

import com.ticketing.queue.application.QueueProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import org.redisson.api.RBucket;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 매크로 탐지 코어.
 *
 * <h2>탐지 근거</h2>
 * <ol>
 *   <li><b>요청 간격 균일성</b> — 사람은 요청 사이 시간이 들쭉날쭉하지만 매크로는 거의 일정하다.
 *       최근 {@code windowSize} 개 요청의 timestamp 간격을 뽑아 변동계수 CV(stdev/mean)를 계산하고,
 *       {@code intervalScore = clamp01(1 - CV)} 로 0~1 정규화. CV 가 작을수록 봇 확률↑.</li>
 *   <li><b>User-Agent 시그니처</b> — 헤더 부재, 또는 자동화 툴이 남기는 시그니처(curl, python, java, go-http 등).
 *       {@code uaScore} 는 0(정상 브라우저) / 0.9(부재) / 1.0(봇 시그니처) 으로 이산화.</li>
 * </ol>
 *
 * <h2>점수 결합</h2>
 * <pre>
 *   botScore = w_i * intervalScore + w_ua * uaScore
 *   botScore > threshold  →  hard block for blockDuration
 * </pre>
 *
 * <h2>Redis 키 모델</h2>
 * <pre>
 *   queue:macro:window:{scope}:{key}    RList<Long>   최근 요청 timestamp 슬라이딩 윈도우
 *   queue:macro:blocked:{scope}:{key}   RBucket<String>  차단 마커 (TTL = blockDuration)
 * </pre>
 *
 * <h2>왜 순수 계산부는 static + package-private 로 노출하는가</h2>
 * <p>
 *   단위 테스트가 Redis 없이 산수 로직만 검증할 수 있게 하기 위함. Redisson 은 통합 테스트에서만 다룬다.
 * </p>
 */
@Service
public class MacroDetectionService {

    private static final Logger log = LoggerFactory.getLogger(MacroDetectionService.class);

    public static final String WINDOW_PREFIX = "queue:macro:window:";
    public static final String BLOCK_PREFIX = "queue:macro:blocked:";

    /**
     * 매크로로 흔히 관측되는 User-Agent 시그니처(소문자 부분 문자열).
     *
     * <p>
     *   완벽한 화이트/블랙리스트가 아니라 <b>지표</b> 로만 활용. 실제 매크로가 브라우저 UA 를 위조해도
     *   interval 신호가 남으므로 두 축의 가중합으로 판정한다.
     * </p>
     */
    static final Set<String> BOT_UA_SIGNATURES = Set.of(
            "curl/",
            "python-requests",
            "python-httpx",
            "python-urllib",
            "aiohttp",
            "go-http-client",
            "java/",           // 표준 java.net.HttpURLConnection 기본 UA
            "okhttp",
            "wget/",
            "libwww-perl",
            "phantomjs",
            "puppeteer",
            "headlesschrome",
            "scrapy"
    );

    private final RedissonClient redisson;
    private final QueueProperties.MacroDetection props;
    private final Clock clock;
    private final Counter detectedCounter;
    private final Counter blockedRequestsCounter;

    public MacroDetectionService(RedissonClient redisson,
                                 QueueProperties.MacroDetection props,
                                 Clock clock,
                                 MeterRegistry meterRegistry) {
        this.redisson = redisson;
        this.props = props;
        this.clock = clock;
        this.detectedCounter = Counter.builder("queue.macro.detected")
                .description("Times a scope was newly flagged as macro")
                .register(meterRegistry);
        this.blockedRequestsCounter = Counter.builder("queue.macro.blocked_requests")
                .description("Requests rejected because the scope is currently blocked")
                .register(meterRegistry);
    }

    /**
     * 스코프×키가 현재 매크로로 차단 중인가?
     *
     * <p>
     *   필터가 매 요청마다 O(1) 로 조회. 차단 마커가 있으면 즉시 403.
     * </p>
     */
    public boolean isBlocked(String scope, String key) {
        RBucket<String> b = redisson.getBucket(blockedKey(scope, key));
        boolean blocked = b.isExists();
        if (blocked) blockedRequestsCounter.increment();
        return blocked;
    }

    /**
     * 요청을 기록하고 즉시 봇 점수를 평가한다.
     *
     * <p>
     *   <b>주의</b>: 차단 상태가 아닐 때만 호출한다({@link #isBlocked}이 false 반환한 경우).
     *   윈도우가 아직 안 찼으면 판정 유예 — 자동 통과.
     * </p>
     *
     * @param scope     스코프 문자열 ("ip" / "user")
     * @param key       스코프 안 식별자 (IP 또는 검증된 사용자 UUID)
     * @param userAgent 요청 UA 헤더 원문
     * @return 판정 결과 (통과 or 새로 차단됨)
     */
    public Decision recordAndEvaluate(String scope, String key, String userAgent) {
        long now = clock.millis();

        // 슬라이딩 윈도우에 timestamp 추가.
        RList<Long> window = redisson.getList(windowKey(scope, key));
        window.add(now);
        // 크기 초과분 head 에서 제거. 정확한 원자성보다 대략의 O(1) 유지가 목적.
        while (window.size() > props.getWindowSize()) {
            window.remove(0);
        }
        // 마지막 접근 이후 windowTtl 동안 요청이 없으면 소멸 — Redis 메모리 낭비 방지.
        window.expire(props.getWindowTtl());

        // 윈도우가 다 안 찼으면 판정 안 함 — 초기 요청 몇 개로 오탐 방지.
        List<Long> timestamps = window.readAll();
        if (timestamps.size() < props.getWindowSize()) {
            return Decision.allow(0.0);
        }

        double intervalScore = computeIntervalScore(timestamps);
        double uaScore = computeUaScore(userAgent);
        double botScore = props.getWeightInterval() * intervalScore
                + props.getWeightUa() * uaScore;

        if (botScore > props.getThreshold()) {
            // 차단 마커 SET EX. 향후 blockDuration 동안 이 스코프는 필터에서 즉시 컷.
            RBucket<String> mark = redisson.getBucket(blockedKey(scope, key));
            mark.set("1", props.getBlockDuration());
            detectedCounter.increment();
            log.info("macro detected scope={} key={} intervalScore={} uaScore={} botScore={}",
                    scope, key, fmt(intervalScore), fmt(uaScore), fmt(botScore));
            return Decision.block(botScore, intervalScore, uaScore);
        }
        return Decision.allow(botScore);
    }

    // -------------------------------------------------------------------------
    // 순수 계산부 — Redis 없이 단위 테스트 가능.
    // -------------------------------------------------------------------------

    /**
     * timestamp 리스트로부터 interval score(0~1) 계산.
     *
     * <p>
     *   반환값은 "봇에 가까울수록 1" 방향. 스토리:
     *   <ol>
     *     <li>정렬 → 인접 차분 → intervals 배열.</li>
     *     <li>평균과 표준편차 계산.</li>
     *     <li>변동계수 CV = stdev/mean. mean=0 은 매우 빠른 연속 요청 → 1.0.</li>
     *     <li>score = clamp01(1 - CV). CV 클수록(들쭉날쭉) score 낮음.</li>
     *   </ol>
     * </p>
     */
    static double computeIntervalScore(List<Long> timestamps) {
        if (timestamps == null || timestamps.size() < 2) return 0.0;
        long[] sorted = timestamps.stream().sorted().mapToLong(Long::longValue).toArray();
        int n = sorted.length - 1;
        double[] intervals = new double[n];
        double sum = 0.0;
        for (int i = 0; i < n; i++) {
            intervals[i] = sorted[i + 1] - sorted[i];
            sum += intervals[i];
        }
        double mean = sum / n;
        // 모든 간격이 0 이면 사실상 동시 요청 폭주 — 최대치.
        if (mean <= 0) return 1.0;
        double varSum = 0.0;
        for (double v : intervals) {
            double d = v - mean;
            varSum += d * d;
        }
        double stdev = Math.sqrt(varSum / n);
        double cv = stdev / mean;
        return clamp01(1.0 - cv);
    }

    /**
     * UA 문자열로부터 봇 확률 score(0~1). 이산화된 룰 기반.
     *
     * <p>
     *   - null/빈 문자열 → 0.9 (사람이라면 브라우저가 UA 를 보낸다)
     *   - 봇 시그니처 부분 문자열 매칭 → 1.0
     *   - 그 외 → 0.0 (판정 유예)
     * </p>
     */
    static double computeUaScore(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) return 0.9;
        String lower = userAgent.toLowerCase(Locale.ROOT);
        for (String sig : BOT_UA_SIGNATURES) {
            if (lower.contains(sig)) return 1.0;
        }
        return 0.0;
    }

    /** 0 이하는 0, 1 이상은 1 로 clamp. */
    private static double clamp01(double v) {
        if (v < 0.0) return 0.0;
        return Math.min(v, 1.0);
    }

    private static String windowKey(String scope, String key) {
        return WINDOW_PREFIX + scope + ":" + key;
    }

    private static String blockedKey(String scope, String key) {
        return BLOCK_PREFIX + scope + ":" + key;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    /**
     * 필터로 돌려주는 판정 결과.
     *
     * <p>{@code blocked} 가 true 면 필터는 즉시 403 을 반환. false 면 다음 필터로 진행.</p>
     */
    public record Decision(boolean blocked, double botScore, double intervalScore, double uaScore) {
        static Decision allow(double botScore) {
            return new Decision(false, botScore, 0.0, 0.0);
        }

        static Decision block(double botScore, double intervalScore, double uaScore) {
            return new Decision(true, botScore, intervalScore, uaScore);
        }
    }

    /** 메트릭 태그 등에서 스코프 문자열이 필요할 때 사용. */
    public static final class Scope {
        public static final String IP = "ip";
        public static final String USER = "user";

        private Scope() {}
    }
}
