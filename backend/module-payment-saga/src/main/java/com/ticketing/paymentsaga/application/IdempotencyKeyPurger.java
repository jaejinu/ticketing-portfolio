package com.ticketing.paymentsaga.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 오래된 멱등 키 정리.
 *
 * <p>
 *   예전엔 지우는 곳이 없어 결제 시도마다 행이 영원히 쌓였다 (2026-10-13 QA P3, 로컬 약 670행 중 630행이
 *   일주일 넘음). 키는 같은 점유의 재시도를 묶는 용도라 점유가 끝난 뒤에는 쓸 일이 없다 — 보존 기간이
 *   지나면 지운다. 결과가 아직 정해지지 않은(PENDING) 결제의 키는 남긴다.
 * </p>
 *
 * <p>지우기는 몇 번 겹쳐 돌아도 결과가 같아 여러 인스턴스 잠금(ShedLock)은 두지 않는다.</p>
 */
@Component
public class IdempotencyKeyPurger {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyKeyPurger.class);
    private static final int BATCH = 1_000;
    private static final int MAX_BATCHES = 50;

    private final JdbcTemplate jdbc;
    private final PaymentProperties props;

    public IdempotencyKeyPurger(JdbcTemplate jdbc, PaymentProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${app.payment.idempotency-key-purge-interval:PT1H}", initialDelayString = "PT2M")
    public void scheduledPurge() {
        purgeOnce();
    }

    /** @return 이번에 지운 행 수 */
    public int purgeOnce() {
        long retentionSeconds = props.getIdempotencyKeyRetention().toSeconds();
        int total = 0;
        for (int i = 0; i < MAX_BATCHES; i++) {
            int deleted = jdbc.update("""
                    DELETE FROM idempotency_keys
                     WHERE (holder_id, idempotency_key) IN (
                           SELECT k.holder_id, k.idempotency_key
                             FROM idempotency_keys k
                            WHERE k.created_at < now() - make_interval(secs => ?)
                              AND NOT EXISTS (SELECT 1 FROM payments p
                                               WHERE p.id = k.payment_id AND p.status = 'PENDING')
                            LIMIT ?)
                    """, retentionSeconds, BATCH);
            total += deleted;
            if (deleted < BATCH) break;
        }
        if (total > 0) {
            log.info("[idempotency] 보존 기간이 지난 멱등 키 {}개를 지웠습니다.", total);
        }
        return total;
    }
}
