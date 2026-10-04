package com.ticketing.queue.application;

import com.ticketing.common.error.BusinessException;
import com.ticketing.common.error.ErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.redisson.api.RBucket;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 가상 대기열 핵심 서비스 — Redis ZSET 기반.
 *
 * <h2>Redis 키 모델</h2>
 * <pre>
 *   queue:waiting:{scheduleId}           ZSET, member = ticketUUID, score = enqueue epoch ms
 *   queue:admitted:{scheduleId}:{ticket} STRING, value = holderId or "anon", TTL = admission-ttl
 *   queue:ticket:{ticket}                STRING, value = scheduleId, TTL = ticket-ttl (역조회)
 * </pre>
 *
 * <h2>왜 score 가 epoch ms 인가</h2>
 * <p>
 *   ZSET 정렬 기준이 곧 enqueue 순서 = 입장 순서. 같은 ms 충돌은 사실상 0.
 *   ZRANK 로 position 조회가 O(log N).
 * </p>
 *
 * <h2>왜 admitted 키를 ticket 별로 분리하는가</h2>
 * <p>
 *   Redis 자체 TTL 로 admission 만료를 자동 처리. SET 안에 넣으면 TTL 을 entry 단위로
 *   적용 못 함. ticket 별 STRING 키가 가장 단순한 분산 처리.
 * </p>
 */
@Service
public class VirtualQueueService {

    private static final Logger log = LoggerFactory.getLogger(VirtualQueueService.class);

    /** 대기 큐 prefix — schedule 단위. */
    public static final String WAITING_PREFIX = "queue:waiting:";
    /** admission 토큰 prefix — schedule × ticket 단위. */
    public static final String ADMITTED_PREFIX = "queue:admitted:";
    /** ticket → scheduleId 역조회 prefix — status 호출 시 어느 큐인지 빠르게 식별. */
    public static final String TICKET_PREFIX = "queue:ticket:";

    private final RedissonClient redissonClient;
    private final QueueProperties props;
    private final Clock clock;
    private final Counter enqueuedCounter;
    private final Counter admittedCounter;

    public VirtualQueueService(RedissonClient redissonClient,
                                QueueProperties props,
                                Clock clock,
                                MeterRegistry meterRegistry) {
        this.redissonClient = redissonClient;
        this.props = props;
        this.clock = clock;
        this.enqueuedCounter = Counter.builder("queue.enqueued")
                .description("Tickets enqueued into waiting queue")
                .register(meterRegistry);
        this.admittedCounter = Counter.builder("queue.admitted")
                .description("Tickets promoted from waiting to admitted")
                .register(meterRegistry);
    }

    /**
     * 대기열 진입. 새 ticket 발급 + ZSET 에 score=now epoch ms 로 추가.
     *
     * @param scheduleId 회차
     * @param holderTag  추적용 (로그인 사용자면 userId, 익명이면 "anon")
     * @return 발급된 ticket id + 현재 상태(보통 WAITING)
     */
    public QueueStatusSnapshot enqueue(UUID scheduleId, String holderTag) {
        UUID ticket = UUID.randomUUID();
        long score = clock.millis();

        // ZSET 에 추가 — Redisson 의 add 는 SADD 와 다르게 score-member 쌍.
        RScoredSortedSet<String> waiting = redissonClient.getScoredSortedSet(
                WAITING_PREFIX + scheduleId);
        waiting.add(score, ticket.toString());

        // ticket → scheduleId 역조회 키 (status 호출 최적화).
        RBucket<String> ticketBucket = redissonClient.getBucket(TICKET_PREFIX + ticket);
        ticketBucket.set(scheduleId.toString(), props.getTicketTtl());

        enqueuedCounter.increment();
        long position = positionOrZero(waiting, ticket);
        log.debug("queue enqueue scheduleId={}, ticket={}, position={}", scheduleId, ticket, position);

        return new QueueStatusSnapshot(
                QueueState.WAITING, ticket, scheduleId,
                position, estimatedWaitSeconds(position));
    }

    /**
     * ticket 의 현재 상태 조회.
     *
     * <p>
     *   조회 우선순위:
     *   <ol>
     *     <li>admitted 키가 살아있나? → ADMITTED</li>
     *     <li>waiting ZSET 안에 있나? → WAITING + position</li>
     *     <li>둘 다 없으면 EXPIRED (재 enqueue 권장)</li>
     *   </ol>
     * </p>
     */
    public QueueStatusSnapshot status(UUID ticket) {
        // ticket → scheduleId 역조회
        RBucket<String> ticketBucket = redissonClient.getBucket(TICKET_PREFIX + ticket);
        String scheduleIdStr = ticketBucket.get();
        if (scheduleIdStr == null) {
            // 역조회 키도 만료 — ticket 자체가 EXPIRED. position 의미 없음.
            return new QueueStatusSnapshot(QueueState.EXPIRED, ticket, null, 0, 0);
        }
        UUID scheduleId = UUID.fromString(scheduleIdStr);

        // admitted 우선 체크.
        RBucket<String> admitted = redissonClient.getBucket(
                ADMITTED_PREFIX + scheduleId + ":" + ticket);
        if (admitted.isExists()) {
            return new QueueStatusSnapshot(QueueState.ADMITTED, ticket, scheduleId, 0, 0);
        }

        // waiting 큐 안에서 position.
        RScoredSortedSet<String> waiting = redissonClient.getScoredSortedSet(
                WAITING_PREFIX + scheduleId);
        Integer rank = waiting.rank(ticket.toString());
        if (rank == null) {
            // ZSET 에서 제거됨 (admit 사이클이 이미 처리했지만 admitted 키가 만료된 경우)
            return new QueueStatusSnapshot(QueueState.EXPIRED, ticket, scheduleId, 0, 0);
        }
        long position = rank.longValue();
        return new QueueStatusSnapshot(
                QueueState.WAITING, ticket, scheduleId,
                position, estimatedWaitSeconds(position));
    }

    /**
     * 회차의 head 에서 최대 {@code count} 개 ticket 을 admit.
     *
     * <p>
     *   - ZSET 의 처음 {@code count} 개를 ZREM
     *   - 각 ticket 에 admission 키 SET EX
     *   - 호출자: {@link QueueAdmissionScheduler}
     * </p>
     */
    public int admitHead(UUID scheduleId, int count) {
        if (count <= 0) return 0;
        RScoredSortedSet<String> waiting = redissonClient.getScoredSortedSet(
                WAITING_PREFIX + scheduleId);
        Collection<String> head = waiting.valueRange(0, count - 1);
        int admitted = 0;
        for (String ticketStr : head) {
            // ZREM
            boolean removed = waiting.remove(ticketStr);
            if (!removed) continue;
            // admission 키 SET EX
            RBucket<String> bucket = redissonClient.getBucket(
                    ADMITTED_PREFIX + scheduleId + ":" + ticketStr);
            bucket.set("admitted", props.getAdmissionTtl());
            admittedCounter.increment();
            admitted++;
        }
        if (admitted > 0) {
            log.debug("queue admitted scheduleId={}, count={}", scheduleId, admitted);
        }
        return admitted;
    }

    /** 회차의 waiting 큐 길이 — Gauge 메트릭 / 운영 모니터링. */
    public long waitingSize(UUID scheduleId) {
        return redissonClient.getScoredSortedSet(WAITING_PREFIX + scheduleId).size();
    }

    /** 입력 검증 — scheduleId 형식 오류 시 INVALID_REQUEST. */
    public void requireValidScheduleId(UUID scheduleId) {
        if (scheduleId == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "scheduleId 가 필요합니다.");
        }
    }

    // -------------------------------------------------------------------------

    private long positionOrZero(RScoredSortedSet<String> set, UUID ticket) {
        Integer rank = set.rank(ticket.toString());
        return rank == null ? 0L : rank.longValue();
    }

    /**
     * 예상 대기 시간 = position / admit-rate-per-sec.
     *
     * <p>
     *   ceiling 처리로 "0초 후 입장" 같은 거짓 응답 방지.
     *   admit-rate=0 이면 무한 — Long.MAX_VALUE 대신 큰 상수.
     * </p>
     */
    long estimatedWaitSeconds(long position) {
        int rate = props.getAdmitRatePerSec();
        if (rate <= 0) return Duration.ofHours(1).toSeconds();
        if (position <= 0) return 0;
        return (position + rate - 1) / rate;
    }
}
