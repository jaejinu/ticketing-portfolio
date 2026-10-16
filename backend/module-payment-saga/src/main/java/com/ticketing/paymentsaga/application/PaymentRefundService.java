package com.ticketing.paymentsaga.application;

import com.ticketing.auth.audit.AuditRecorder;
import com.ticketing.common.error.BusinessException;
import com.ticketing.common.error.ErrorCode;
import com.ticketing.events.Topics;
import com.ticketing.outbox.OutboxWriter;
import com.ticketing.paymentsaga.domain.Payment;
import com.ticketing.paymentsaga.domain.PaymentRepository;
import com.ticketing.paymentsaga.domain.PaymentStatus;
import com.ticketing.paymentsaga.outbox.PaymentEventPayloads;
import com.ticketing.seat.application.SeatHoldService;
import com.ticketing.show.domain.ShowSchedule;
import com.ticketing.show.domain.ShowScheduleRepository;
import com.ticketing.show.domain.ShowScheduleStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Persist refund intent, cancel outside DB transactions, then settle under a fenced lease. */
@Service
public class PaymentRefundService {

    private static final Logger log = LoggerFactory.getLogger(PaymentRefundService.class);

    private final PaymentRepository payments;
    private final ShowScheduleRepository schedules;
    private final SeatHoldService seatHoldService;
    private final MockPgClient pg;
    private final OutboxWriter outbox;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final AuditRecorder audit;
    private final PaymentProperties props;
    private final java.time.Clock clock;

    public PaymentRefundService(PaymentRepository payments, ShowScheduleRepository schedules,
                                SeatHoldService seatHoldService, MockPgClient pg, OutboxWriter outbox,
                                ObjectMapper json, TransactionTemplate tx, AuditRecorder audit, PaymentProperties props, java.time.Clock clock) {
        this.payments = payments;
        this.schedules = schedules;
        this.seatHoldService = seatHoldService;
        this.pg = pg;
        this.outbox = outbox;
        this.json = json;
        this.tx = tx;
        this.audit = audit;
        this.props = props;
        this.clock = clock;
    }

    private java.time.OffsetDateTime now() { return java.time.OffsetDateTime.now(clock); }
    private record Work(Payment payment, UUID token) {}

    public Payment refund(UUID actorId, UUID paymentId, String reason) {
        String why = reason == null || reason.isBlank() ? "admin" : reason;
        Work work = tx.execute(status -> {
            Payment p = payments.findForUpdate(paymentId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.SEAT_NOT_FOUND, "결제를 찾을 수 없습니다."));
            if (PaymentStatus.REFUNDED.equals(p.getStatus())) return new Work(p, null);
            if (!PaymentStatus.APPROVED.equals(p.getStatus())) {
                throw new BusinessException(ErrorCode.PAYMENT_ALREADY_SETTLED, "승인된 결제만 환불할 수 있습니다.");
            }
            if (p.isRefundPending() && p.getReconcileAt().isAfter(now())) return new Work(p, null);
            p.requestRefund(actorId, why);
            return new Work(p, p.claim(now().plus(props.getProcessingLease())));
        });
        if (work.token() == null) return work.payment();
        return complete(work);
    }

    public void reconcileDue() {
        if (!props.isReconcileEnabled()) return;
        for (UUID id : payments.findRefundsDue(now(), org.springframework.data.domain.PageRequest.of(0, props.getReconcileBatchSize()))) {
            try {
                Work work = tx.execute(status -> {
                    Payment p = payments.findForUpdate(id).orElse(null);
                    if (p == null || !p.isRefundPending() || p.getReconcileAt().isAfter(now())) return null;
                    return new Work(p, p.claim(now().plus(props.getProcessingLease())));
                });
                if (work != null) complete(work);
            } catch (RuntimeException ex) {
                log.warn("Refund recovery deferred payment={} cause={}", id, ex.toString());
            }
        }
    }

    private Payment complete(Work work) {
        UUID paymentId = work.payment().getId();
        try {
            pg.cancel(paymentId);
        } catch (PgUnavailableException ex) {
            tx.executeWithoutResult(status -> {
                Payment p = payments.findForUpdate(paymentId).orElseThrow();
                if (p.ownsRefund(work.token())) p.retryAt(now().plus(props.getReconcileRetry()));
            });
            throw new BusinessException(ErrorCode.PAYMENT_PG_FAILURE, "환불 요청을 저장했습니다. 결제사 응답을 자동으로 다시 확인합니다.");
        }
        // If this transaction fails, the persisted intent and lease remain recoverable.
        return tx.execute(status -> {
            Payment p = payments.findForUpdate(paymentId).orElseThrow();
            if (!p.ownsRefund(work.token())) return p;
            p.refund();
            seatHoldService.returnSoldSeats(p.getHoldId());
            outbox.stage("Payment", p.getId(), "PAYMENT_REFUNDED", Topics.PAYMENT_REFUNDED,
                    PaymentEventPayloads.refunded(json, p, p.getRefundReason()), PaymentEventPayloads.VERSION_V1);
            audit.record(p.getRefundActorId(), "PAYMENT_REFUNDED", "PAYMENT", p.getId(),
                    Map.of("amount", p.getAmount(), "scheduleId", p.getScheduleId().toString(),
                            "holderId", p.getHolderId().toString(), "reason", p.getRefundReason()));
            log.info("[admin] actor={} refunded payment={} amount={}", p.getRefundActorId(), p.getId(), p.getAmount());
            return p;
        });
    }

    /**
     * 종료된 회차의 승인 결제를 모두 환불한다 (공연 취소). 판매 중인 회차는 거절 — 먼저 회차를 닫는다.
     * 한 건이 PG 문제로 실패해도 나머지는 계속하고, 실패 목록을 돌려준다(다시 호출하면 남은 것만 처리).
     */
    public RefundBatch refundSchedule(UUID actorId, UUID scheduleId, String reason) {
        ShowSchedule schedule = schedules.findById(scheduleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SCHEDULE_NOT_FOUND, "회차를 찾을 수 없습니다."));
        if (schedule.getStatus() != ShowScheduleStatus.CLOSED) {
            throw new BusinessException(ErrorCode.INVALID_SHOW_STATE,
                    "일괄 환불은 종료된 회차에서만 할 수 있습니다. 먼저 회차를 닫으세요. status=" + schedule.getStatus());
        }
        List<UUID> refunded = new ArrayList<>();
        List<UUID> failed = new ArrayList<>();
        for (Payment p : payments.findByScheduleIdAndStatusOrderByCreatedAtAsc(scheduleId, PaymentStatus.APPROVED)) {
            try {
                Payment result = refund(actorId, p.getId(), reason);
                if (PaymentStatus.REFUNDED.equals(result.getStatus())) refunded.add(p.getId());
                else failed.add(p.getId());
            } catch (BusinessException ex) {
                log.warn("[admin] schedule refund deferred payment={} cause={}", p.getId(), ex.getMessage());
                failed.add(p.getId());
            }
        }
        audit.record(actorId, "SCHEDULE_REFUNDED", "SCHEDULE", scheduleId,
                Map.of("refunded", refunded.size(), "failed", failed.size()));
        return new RefundBatch(refunded, failed);
    }

    public record RefundBatch(List<UUID> refunded, List<UUID> failed) {}
}
