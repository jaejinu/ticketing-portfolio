package com.ticketing.queue.application.admin;

import com.ticketing.queue.application.QueueProperties;
import com.ticketing.queue.application.VirtualQueueService;
import com.ticketing.queue.application.macro.MacroDetectionService;
import com.ticketing.show.domain.Show;
import com.ticketing.show.domain.ShowRepository;
import com.ticketing.show.domain.ShowSchedule;
import com.ticketing.show.domain.ShowScheduleRepository;
import com.ticketing.show.domain.ShowScheduleStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 운영자 콘솔용 대기열/방어 시스템 조회·조작 서비스.
 *
 * <h2>제공 기능</h2>
 * <ol>
 *   <li><b>매크로 차단 목록</b> — {@code queue:macro:blocked:*} 키를 SCAN 해 현재 차단 중인
 *       (scope, key) 와 남은 차단 시간을 나열.</li>
 *   <li><b>차단 수동 해제</b> — 오탐 신고 대응. 차단 마커와 관측 윈도우를 함께 삭제한다.</li>
 *   <li><b>대기열 현황</b> — ON_SALE 회차별 waiting 큐 길이 + 예상 소진 시간.</li>
 *   <li><b>트래픽 스냅샷</b> — 레이트리밋 허용/차단, 매크로 탐지 카운터의 현재 값.</li>
 * </ol>
 *
 * <h2>SCAN 사용에 대해</h2>
 * <p>
 *   Redisson 의 {@code getKeysByPattern} 은 내부적으로 SCAN(비차단 순회) 을 사용한다.
 *   차단 키 수는 "현재 차단 중인 봇 수" 라 수백~수천 수준 — 운영자 조회 용도로 충분하다.
 *   (수십만 규모가 되면 차단 시 별도 인덱스 SET 에 등록하는 방식으로 전환.)
 * </p>
 *
 * <h2>트래픽 카운터의 한계</h2>
 * <p>
 *   Micrometer Counter 는 <b>인스턴스 메모리</b> 값 — 앱 재시작 시 0 부터 다시 센다.
 *   시계열 히스토리는 Grafana(Mimir) 가 정본이고, 이 API 는 운영자 콘솔에서
 *   "지금 이 순간 얼마나 막고 있나" 를 한눈에 보여주는 스냅샷 용도다.
 * </p>
 */
@Service
public class QueueAdminService {

    private static final Logger log = LoggerFactory.getLogger(QueueAdminService.class);

    private final RedissonClient redisson;
    private final VirtualQueueService queueService;
    private final QueueProperties props;
    private final ShowScheduleRepository scheduleRepository;
    private final ShowRepository showRepository;
    private final MeterRegistry meterRegistry;

    public QueueAdminService(RedissonClient redisson,
                             VirtualQueueService queueService,
                             QueueProperties props,
                             ShowScheduleRepository scheduleRepository,
                             ShowRepository showRepository,
                             MeterRegistry meterRegistry) {
        this.redisson = redisson;
        this.queueService = queueService;
        this.props = props;
        this.scheduleRepository = scheduleRepository;
        this.showRepository = showRepository;
        this.meterRegistry = meterRegistry;
    }

    // -------------------------------------------------------------------------
    // 매크로 차단 목록 / 해제
    // -------------------------------------------------------------------------

    /**
     * 현재 차단 중인 매크로 목록.
     *
     * <p>남은 시간이 긴 순(= 최근에 차단된 순) 으로 정렬 — 운영자가 "방금 무슨 일이 있었나" 를 먼저 본다.</p>
     */
    public List<MacroBlock> listMacroBlocks() {
        List<MacroBlock> out = new ArrayList<>();
        for (String fullKey : redisson.getKeys()
                .getKeysByPattern(MacroDetectionService.BLOCK_PREFIX + "*")) {
            String rest = fullKey.substring(MacroDetectionService.BLOCK_PREFIX.length());
            // 키 형식: {scope}:{key}. key 자체가 콜론을 포함할 수 있으므로(IPv6) 첫 콜론에서만 자른다.
            int colon = rest.indexOf(':');
            if (colon <= 0) continue;
            String scope = rest.substring(0, colon);
            String key = rest.substring(colon + 1);

            long ttlMillis = redisson.getBucket(fullKey).remainTimeToLive();
            // -2: SCAN 과 조회 사이에 만료(경합) — 이미 풀린 차단이므로 목록에서 제외.
            if (ttlMillis == -2) continue;
            // -1: TTL 없음 — 정상 흐름에선 없지만 수동 조작 잔재일 수 있어 0초로 표시만 한다.
            long remainingSeconds = ttlMillis < 0 ? 0 : (ttlMillis + 999) / 1000;
            out.add(new MacroBlock(scope, key, remainingSeconds));
        }
        out.sort(Comparator.comparingLong(MacroBlock::remainingSeconds).reversed());
        return out;
    }

    /**
     * 차단 수동 해제 (오탐 대응).
     *
     * <p>
     *   차단 마커만 지우면 관측 윈도우에 남은 "균일 간격" timestamp 들 때문에
     *   다음 요청에서 즉시 재차단될 수 있다. 윈도우도 함께 삭제해 관측을 처음부터 다시 시작한다.
     * </p>
     *
     * @return 차단 마커가 실제로 존재했으면 true (없었으면 이미 만료된 것 — 404 응답용)
     */
    public boolean unblockMacro(String scope, String key) {
        String blockedKey = MacroDetectionService.BLOCK_PREFIX + scope + ":" + key;
        String windowKey = MacroDetectionService.WINDOW_PREFIX + scope + ":" + key;

        boolean existed = redisson.getBucket(blockedKey).delete();
        redisson.getKeys().delete(windowKey);
        if (existed) {
            log.info("macro unblocked by admin scope={} key={}", scope, key);
        }
        return existed;
    }

    // -------------------------------------------------------------------------
    // 대기열 현황
    // -------------------------------------------------------------------------

    /**
     * ON_SALE 회차별 대기열 현황.
     *
     * <p>
     *   waiting 큐 길이는 Redis ZCARD(O(1)), 공연 제목은 한 번의 IN 조회로 붙인다.
     *   예상 소진 시간 = waiting / admit-rate (ceiling) — 사용자에게 보여주는 예상 대기시간과 같은 산식.
     * </p>
     */
    public List<ScheduleQueue> queueOverview() {
        List<ShowSchedule> active = scheduleRepository.findByStatus(ShowScheduleStatus.ON_SALE);
        if (active.isEmpty()) return List.of();

        // showId → title 매핑 — N+1 방지를 위해 IN 한 방.
        List<UUID> showIds = active.stream().map(ShowSchedule::getShowId).distinct().toList();
        Map<UUID, String> titles = showRepository.findAllById(showIds).stream()
                .collect(Collectors.toMap(Show::getId, Show::getTitle));

        int rate = props.getAdmitRatePerSec();
        List<ScheduleQueue> out = new ArrayList<>(active.size());
        for (ShowSchedule s : active) {
            long waiting = queueService.waitingSize(s.getId());
            long drainSeconds = rate <= 0 ? 0 : (waiting + rate - 1) / rate;
            out.add(new ScheduleQueue(
                    s.getId(), s.getShowId(),
                    titles.getOrDefault(s.getShowId(), "(제목 없음)"),
                    s.getStartsAt(), waiting, rate, drainSeconds));
        }
        // 대기 인원 많은 회차가 위로 — 지금 부하가 몰리는 곳부터 보인다.
        out.sort(Comparator.comparingLong(ScheduleQueue::waiting).reversed());
        return out;
    }

    // -------------------------------------------------------------------------
    // 트래픽 스냅샷
    // -------------------------------------------------------------------------

    /** 레이트리밋/매크로 카운터의 현재 값. 카운터가 아직 등록 전(트래픽 0)이면 0. */
    public TrafficStats trafficStats() {
        return new TrafficStats(
                new ScopeCounters(
                        counterValue("queue.ratelimit.allowed", "scope", "ip"),
                        counterValue("queue.ratelimit.blocked", "scope", "ip")),
                new ScopeCounters(
                        counterValue("queue.ratelimit.allowed", "scope", "user"),
                        counterValue("queue.ratelimit.blocked", "scope", "user")),
                counterValue("queue.macro.detected"),
                counterValue("queue.macro.blocked_requests"));
    }

    /**
     * 이름(+태그) 로 카운터 현재 값을 읽는다.
     *
     * <p>Micrometer 카운터는 첫 increment 때 lazy 등록 — 아직 없으면 "한 번도 안 일어난 일" 이므로 0.</p>
     */
    private long counterValue(String name, String... tagKeyValues) {
        Counter c = meterRegistry.find(name).tags(tagKeyValues).counter();
        return c == null ? 0L : (long) c.count();
    }

    // -------------------------------------------------------------------------
    // 조회 결과 모델 — Jackson 이 record 컴포넌트명 그대로 직렬화한다.
    // -------------------------------------------------------------------------

    /** 차단 중인 매크로 하나. key 는 IP 또는 검증된 사용자 UUID (raw 토큰은 애초에 저장 안 함). */
    public record MacroBlock(String scope, String key, long remainingSeconds) {
    }

    /** ON_SALE 회차 하나의 대기열 현황. */
    public record ScheduleQueue(UUID scheduleId, UUID showId, String showTitle,
                                OffsetDateTime startsAt, long waiting,
                                int admitRatePerSec, long estimatedDrainSeconds) {
    }

    /** 스코프(ip/user) 하나의 레이트리밋 허용/차단 누적. */
    public record ScopeCounters(long allowed, long blocked) {
    }

    /** 트래픽 방어 스냅샷 — 인스턴스 기동 이후 누적값. */
    public record TrafficStats(ScopeCounters ip, ScopeCounters user,
                               long macroDetected, long macroBlockedRequests) {
    }
}
