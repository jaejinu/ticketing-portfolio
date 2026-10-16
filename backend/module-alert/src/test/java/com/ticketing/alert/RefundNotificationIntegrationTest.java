package com.ticketing.alert;

import com.ticketing.alert.refund.RefundNotificationQueue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class RefundNotificationIntegrationTest extends AlertIntegrationTestBase {
    @Autowired RefundNotificationQueue queue;
    @Autowired JdbcTemplate jdbc;
    @Test
    void duplicateEventsRetryAndStaleLeaseCannotOverwriteNewAttempt() {
        jdbc.update("DELETE FROM refund_notifications");
        UUID payment = UUID.randomUUID(), user = UUID.randomUUID();
        queue.enqueue(payment, user, 100000);
        queue.enqueue(payment, user, 100000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refund_notifications", Long.class)).isEqualTo(2);
        var first = queue.claim().getFirst();
        queue.complete(first, "IOException");
        assertThat(jdbc.queryForObject("SELECT status FROM refund_notifications WHERE payment_id=? AND channel=?", String.class, payment, first.channel())).isEqualTo("PENDING");
        jdbc.update("UPDATE refund_notifications SET next_attempt_at=now()-interval '1 second'");
        var second = queue.claim().getFirst();
        jdbc.update("UPDATE refund_notifications SET next_attempt_at=now()-interval '1 second' WHERE payment_id=? AND channel=?", second.paymentId(), second.channel());
        // Claim the exact expired row, with the other channel deferred.
        jdbc.update("UPDATE refund_notifications SET next_attempt_at=now()+interval '1 hour' WHERE channel<>?", second.channel());
        var replacement = queue.claim().getFirst();
        assertThat(replacement.token()).isNotEqualTo(second.token());
        queue.complete(second, null);
        assertThat(jdbc.queryForObject("SELECT status FROM refund_notifications WHERE channel=?", String.class, second.channel())).isEqualTo("SENDING");
        queue.complete(replacement, null);
        assertThat(jdbc.queryForObject("SELECT status FROM refund_notifications WHERE channel=?", String.class, replacement.channel())).isEqualTo("SENT");
        jdbc.update("DELETE FROM refund_notifications");
    }
}
