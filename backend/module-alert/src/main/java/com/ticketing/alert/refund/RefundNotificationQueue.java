package com.ticketing.alert.refund;

import com.ticketing.events.Envelope;
import com.ticketing.events.Topics;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** Durable handoff: consumer commits only after both channels have been recorded. */
@Service
public class RefundNotificationQueue {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public RefundNotificationQueue(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }
    @KafkaListener(topics = Topics.PAYMENT_REFUNDED, groupId = "${app.alert.refund-consumer-group:refund-notifications}")
    @Transactional
    public void onRefund(String raw) {
        var payload = json.readValue(raw, Envelope.class).payload();
        enqueueConfirmed(UUID.fromString(payload.get("paymentId").asText()));
    }
    /** Shared brokers can contain events from another database. The local payment is authoritative. */
    public int enqueueConfirmed(UUID paymentId) {
        return jdbc.update("""
            INSERT INTO refund_notifications(payment_id,channel,user_id,amount)
            SELECT p.id,c.channel,p.holder_id,p.amount FROM payments p
            CROSS JOIN (VALUES ('SMTP'),('FCM')) AS c(channel)
            WHERE p.id=? AND p.status='REFUNDED'
            ON CONFLICT (payment_id,channel) DO NOTHING
            """, paymentId);
    }
    @Transactional
    public void enqueue(UUID paymentId, UUID userId, long amount) {
        for (String channel : List.of("SMTP", "FCM")) jdbc.update("""
            INSERT INTO refund_notifications(payment_id,channel,user_id,amount) VALUES (?,?,?,?)
            ON CONFLICT (payment_id,channel) DO NOTHING
            """, paymentId, channel, userId, amount);
    }
    /** Reconcile confirmed refunds even if Kafka retention or delivery was interrupted. Gateway-only worker. */
    public int backfill() {
        return jdbc.update("""
            INSERT INTO refund_notifications(payment_id,channel,user_id,amount)
            SELECT p.id,c.channel,p.holder_id,p.amount FROM payments p
            CROSS JOIN (VALUES ('SMTP'),('FCM')) AS c(channel)
            WHERE p.status='REFUNDED' AND NOT EXISTS (
                SELECT 1 FROM refund_notifications n WHERE n.payment_id=p.id AND n.channel=c.channel)
            ORDER BY p.created_at LIMIT 100 ON CONFLICT (payment_id,channel) DO NOTHING
            """);
    }
    public record Work(UUID paymentId, String channel, UUID userId, long amount, int attempts, UUID token) {}
    @Transactional
    public List<Work> claim() {
        UUID token = UUID.randomUUID();
        return jdbc.query("""
            UPDATE refund_notifications n SET status='SENDING', attempts=attempts+1,
                lease_token=?, next_attempt_at=now()+interval '2 minutes'
            WHERE (n.payment_id,n.channel) IN (
                SELECT payment_id,channel FROM refund_notifications
                WHERE status IN ('PENDING','SENDING') AND next_attempt_at <= now()
                ORDER BY next_attempt_at LIMIT 1 FOR UPDATE SKIP LOCKED)
            RETURNING n.payment_id,n.channel,n.user_id,n.amount,n.attempts
            """, (rs, i) -> new Work(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class), rs.getLong(4), rs.getInt(5), token), token);
    }
    public void complete(Work w, String error) {
        jdbc.update("""
            UPDATE refund_notifications SET status=?, lease_token=null, last_error=?,
                sent_at=CASE WHEN ? THEN now() ELSE sent_at END,
                next_attempt_at=now() + (? * interval '1 second')
            WHERE payment_id=? AND channel=? AND lease_token=? AND status='SENDING'
            """, error == null ? "SENT" : w.attempts() >= 10 ? "FAILED" : "PENDING",
                error, error == null, Math.min(3600, 30L << Math.min(w.attempts(), 6)), w.paymentId(), w.channel(), w.token());
    }
}
