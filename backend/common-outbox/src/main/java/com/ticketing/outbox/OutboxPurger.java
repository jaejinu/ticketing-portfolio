package com.ticketing.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 발행 끝난 outbox 행을 보존 기간이 지나면 지운다.
 *
 * <h2>왜 나눠서 지우는가</h2>
 * <p>
 *   한 번에 수십만 행을 지우면 긴 트랜잭션·WAL 폭주로 발행기({@link OutboxPublisher})가 밀린다.
 *   {@code batch-size} 씩 짧은 DELETE 를 반복하고, 한 사이클 상한을 넘으면 다음 사이클로 넘긴다.
 *   {@code outbox_events_published_at_idx}(PUBLISHED partial) 가 오래된 행을 바로 찾게 한다.
 * </p>
 *
 * <p>다중 인스턴스에서는 ShedLock({@code outbox-purge}) 이 한 인스턴스만 실행되게 한다.</p>
 */
@Component
public class OutboxPurger {

    private static final Logger log = LoggerFactory.getLogger(OutboxPurger.class);

    private final JdbcTemplate jdbc;
    private final OutboxProperties props;
    private final Counter purgedCounter;

    public OutboxPurger(JdbcTemplate jdbc, OutboxProperties props, MeterRegistry meterRegistry) {
        this.jdbc = jdbc;
        this.props = props;
        this.purgedCounter = Counter.builder("outbox.purged")
                .description("PUBLISHED outbox rows deleted after retention")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${app.outbox.purge.interval:PT10M}", initialDelayString = "PT1M")
    @SchedulerLock(name = "outbox-purge", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void scheduledPurge() {
        if (props.getPurge().isEnabled()) {
            purgeOnce();
        }
    }

    /** @return 이번 사이클에서 지운 행 수 */
    public int purgeOnce() {
        OutboxProperties.Purge purge = props.getPurge();
        long retentionSeconds = purge.getRetention().toSeconds();
        int total = 0;
        for (int i = 0; i < purge.getMaxBatchesPerRun(); i++) {
            int deleted = jdbc.update("""
                    DELETE FROM outbox_events
                     WHERE id IN (
                           SELECT id FROM outbox_events
                            WHERE status = 'PUBLISHED'
                              AND published_at < now() - make_interval(secs => ?)
                            ORDER BY published_at
                            LIMIT ?)
                    """, retentionSeconds, purge.getBatchSize());
            total += deleted;
            if (deleted < purge.getBatchSize()) {
                break;
            }
        }
        if (total > 0) {
            purgedCounter.increment(total);
            log.info("outbox purge deleted {} PUBLISHED row(s) older than {}", total, purge.getRetention());
        }
        return total;
    }
}
