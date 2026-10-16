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
 *   queue:holder:{ticket}                STRING, value = holderId, TTL = ticket-ttl
 *   queue:admitted-user:{scheduleId}:{holderId}
 *                                        STRING, value = ticket, TTL = admission-ttl
 *                                        좌석 점유가 "이 사용자가 입장했나" 를 묻는 키
 * </pre>
 *
 * <h2>왜 사용자 단위 입장 키를 따로 두는가</h2>
 * <p>
 *   입장 키는 ticket 단위라 누가 받은 입장인지 알 수 없었다. 좌석 점유는 사용자 id 로만
 *   들어오므로, 입장 순간에 사용자 단위 키를 같이 세워 {@link #isAdmitted} 가 O(1) 로 답한다.
 * </p>
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
    /** ticket → 발급받은 사용자 — admit 시 사용자 단위 입장 키를 세우는 데 사용. */
    public static final String HOLDER_PREFIX = "queue:holder:";
    /** 사용자 단위 입장 키 prefix — schedule × holder 단위. */
    public static final String ADMITTED_USER_PREFIX = "queue:admitted-user:";
    /** 회차·사용자별 현재 표 — 같은 사람이 줄을 여러 번 서지 못하게. */
    public static final String USER_TICKET_PREFIX = "queue:user-ticket:";

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
        // 같은 사람이 이미 줄에 있거나 입장했으면 그 표를 그대로 돌려준다. 예전엔 부를 때마다 새 표가
        // 생겨 한 사람이 줄 여러 자리를 차지했다 (2026-10-13 QA P2).
        RBucket<String> userTicket = redissonClient.getBucket(USER_TICKET_PREFIX + scheduleId + ":" + holderTag);
        String existing = userTicket.get();
        if (existing != null) {
            QueueStatusSnapshot current = status(UUID.fromString(existing), holderTag);
            if (current.state() == QueueState.WAITING || current.state() == QueueState.ADMITTED) {
                return current;
            }
        }

        UUID ticket = UUID.randomUUID();
        long score = clock.millis();

        // ZSET 에 추가 — Redisson 의 add 는 SADD 와 다르게 score-member 쌍.
        RScoredSortedSet<String> waiting = redissonClient.getScoredSortedSet(
                WAITING_PREFIX + scheduleId);
        waiting.add(score, ticket.toString());
        // 줄 자체도 만료를 둔다 — 아무도 새로 서지 않는 회차(종료·취소)의 키가 영원히 남았다.
        waiting.expire(props.getTicketTtl());

        // ticket → scheduleId 역조회 키 (status 호출 최적화).
        RBucket<String> ticketBucket = redissonClient.getBucket(TICKET_PREFIX + ticket);
        ticketBucket.set(scheduleId.toString(), props.getTicketTtl());
        redissonClient.getBucket(HOLDER_PREFIX + ticket).set(holderTag, props.getTicketTtl());
        userTicket.set(ticket.toString(), props.getTicketTtl());

        enqueuedCounter.increment();
        long position = positionOrZero(waiting, ticket);
        log.debug("queue enqueue scheduleId={}, ticket={}, position={}", scheduleId, ticket, position);

        return new QueueStatusSnapshot(
                QueueState.WAITING, ticket, scheduleId,
                position, estimatedWaitSeconds(position));
    }

    /**
     * 본인 표만 조회한다. 남의 표 번호를 알아도 위치·입장 여부를 볼 수 없게 — 예전엔 누구 표든 보였다.
     * 주인이 다르면 없는 표와 똑같이 NOT_FOUND.
     */
    public QueueStatusSnapshot status(UUID ticket, String holderTag) {
        String holder = redissonClient.<String>getBucket(HOLDER_PREFIX + ticket).get();
        if (holder != null && !holder.equals(holderTag)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "대기표를 찾을 수 없습니다.");
        }
        return status(ticket);
    }

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
        // ticket-ttl 보다 오래 기다린 표는 표 키가 이미 만료된 좀비 — 위치 계산에서 빠지도록 먼저 지운다.
        waiting.removeRangeByScore(0, true, clock.millis() - props.getTicketTtl().toMillis(), true);
        Collection<String> head = waiting.valueRange(0, count - 1);
        int admitted = 0;
        for (String ticketStr : head) {
            // ZREM
            boolean removed = waiting.remove(ticketStr);
            if (!removed) continue;
            String holder = redissonClient.<String>getBucket(HOLDER_PREFIX + ticketStr).get();
            // 주인 정보가 사라진 표는 입장시킬 사람이 없다 — 예전엔 "anon" 으로 입장 자리를 낭비했다.
            if (holder == null) continue;
            // admission 키 SET EX
            redissonClient.getBucket(ADMITTED_PREFIX + scheduleId + ":" + ticketStr)
                    .set(holder, props.getAdmissionTtl());
            redissonClient.getBucket(ADMITTED_USER_PREFIX + scheduleId + ":" + holder)
                    .set(ticketStr, props.getAdmissionTtl());
            admittedCounter.increment();
            admitted++;
        }
        if (admitted > 0) {
            log.debug("queue admitted scheduleId={}, count={}", scheduleId, admitted);
        }
        return admitted;
    }

    /** 사용자가 해당 회차에 입장해 있고 입장 유효 시간이 남았는지. */
    public boolean isAdmitted(UUID scheduleId, String holderTag) {
        return redissonClient.getBucket(ADMITTED_USER_PREFIX + scheduleId + ":" + holderTag).isExists();
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
