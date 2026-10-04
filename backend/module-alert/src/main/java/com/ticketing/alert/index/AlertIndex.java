package com.ticketing.alert.index;

import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 알람 빠른 평가용 Redis Set 인덱스.
 *
 * <h2>키 모델</h2>
 * <pre>
 *   alert:by-section:{sectionId}   SET, member = alertId(UUID String)
 * </pre>
 *
 * <h2>왜 Redis Set 인가</h2>
 * <p>
 *   PRICING_TICK 평가 핫 패스: 가격 변동 1건마다 "이 section 에 활성 알람이 있나?" 를 O(1) 조회.
 *   100k 알람 중 section 당 활성 알람은 통상 적음 (수십~수백) → SMEMBERS 한 번 + DB lookup.
 *   KPI: 활성 100k 알람 p99 10ms.
 * </p>
 *
 * <h2>일관성</h2>
 * <p>
 *   index 와 DB 의 정합은 application 레이어 책임:
 *   <ul>
 *     <li>create  : DB insert (TX) → Redis SADD (post-commit 권장이지만 본 PR 단순화 — 동일 메서드 안에)</li>
 *     <li>trigger : DB update (TX) → Redis SREM</li>
 *     <li>disable : DB update → Redis SREM</li>
 *   </ul>
 *   Redis 가 잠시 누락되어도 DB 가 truth — 부팅 시 워밍업 (Phase 7b 도입) 으로 복구.
 * </p>
 */
@Component
public class AlertIndex {

    public static final String SECTION_PREFIX = "alert:by-section:";

    private final RedissonClient redissonClient;

    public AlertIndex(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    public void addActive(UUID sectionId, UUID alertId) {
        set(sectionId).add(alertId.toString());
    }

    public void remove(UUID sectionId, UUID alertId) {
        set(sectionId).remove(alertId.toString());
    }

    /** 평가 엔진의 핫 패스 — section 의 활성 알람 id 집합. */
    public Set<UUID> activeIdsBySection(UUID sectionId) {
        return set(sectionId).readAll().stream()
                .map(UUID::fromString)
                .collect(Collectors.toUnmodifiableSet());
    }

    public long size(UUID sectionId) {
        return set(sectionId).size();
    }

    private RSet<String> set(UUID sectionId) {
        return redissonClient.getSet(SECTION_PREFIX + sectionId);
    }
}
