package com.ticketing.paymentsaga.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.common.error.BusinessException;
import com.ticketing.common.error.ErrorCode;
import com.ticketing.events.Topics;
import com.ticketing.outbox.OutboxWriter;
import com.ticketing.paymentsaga.domain.IdempotencyKey;
import com.ticketing.paymentsaga.domain.IdempotencyKeyRepository;
import com.ticketing.paymentsaga.domain.Payment;
import com.ticketing.paymentsaga.domain.PaymentRepository;
import com.ticketing.paymentsaga.outbox.PaymentEventPayloads;
import com.ticketing.seat.application.SeatHoldService;
import com.ticketing.seat.application.SeatHoldViewService;
import com.ticketing.seat.domain.SeatHold;
import com.ticketing.seat.domain.SeatHoldRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.domain.PageRequest;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * TX1에서 사용자별 요청 잠금 + 점유 행 잠금으로 결제를 생성한다.
 * 최초 생성자만 PG를 호출한다. 재요청은 PENDING을 포함한 기존 결과를 반환한다.
 * PG 결과 불명은 PENDING으로 유지하고, DB 처리 토큰을 획득한 복구 작업이 조회한다.
 * 외부 HTTP 호출 중에는 DB 트랜잭션을 열어 두지 않는다.
 */
@Service
public class PaymentSagaService {

    private static final Logger log = LoggerFactory.getLogger(PaymentSagaService.class);
    private static final String AGGREGATE_PAYMENT = "Payment";

    private final PaymentRepository paymentRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final SeatHoldService seatHoldService;
    private final SeatHoldRepository seatHoldRepository;
    private final SeatHoldViewService seatHoldViewService;
    private final MockPgClient mockPgClient;
    private final OutboxWriter outboxWriter;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;
    private final PaymentProperties props;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    // ---- 메트릭 ---------------------------------------------------------------
    private final Counter approvedCounter;
    private final Counter failedCounter;
    private final Counter idempotentReplayCounter;
    private final Timer pgCallTimer;

    public PaymentSagaService(PaymentRepository paymentRepository,
                               IdempotencyKeyRepository idempotencyKeyRepository,
                               SeatHoldService seatHoldService,
                               SeatHoldRepository seatHoldRepository,
                               SeatHoldViewService seatHoldViewService,
                               MockPgClient mockPgClient,
                               OutboxWriter outboxWriter,
                               ObjectMapper objectMapper,
                               TransactionTemplate txTemplate,
                               PaymentProperties props,
                               MeterRegistry meterRegistry,
                               JdbcTemplate jdbc, Clock clock) {
        this.paymentRepository = paymentRepository;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.seatHoldService = seatHoldService;
        this.seatHoldRepository = seatHoldRepository;
        this.seatHoldViewService = seatHoldViewService;
        this.mockPgClient = mockPgClient;
        this.outboxWriter = outboxWriter;
        this.objectMapper = objectMapper;
        this.txTemplate = txTemplate;
        this.props = props;
        this.jdbc = jdbc;
        this.clock = clock;
        if (props.getProcessingLease() == null || props.getProcessingLease().isNegative()
                || props.getProcessingLease().isZero() || props.getReconcileRetry() == null
                || props.getReconcileRetry().isNegative() || props.getReconcileRetry().isZero()
                || props.getReconcileBatchSize() < 1) {
            throw new IllegalArgumentException("payment recovery durations and batch size must be positive");
        }

        this.approvedCounter = Counter.builder("payment.approved")
                .description("Payments approved by mock PG")
                .register(meterRegistry);
        this.failedCounter = Counter.builder("payment.failed")
                .description("Payments declined or cancellation confirmed by PG")
                .register(meterRegistry);
        this.idempotentReplayCounter = Counter.builder("payment.idempotent.replay")
                .description("Idempotency-Key replays returning existing payment")
                .register(meterRegistry);
        this.pgCallTimer = Timer.builder("payment.pg.call.duration")
                .description("Mock PG call latency")
                .register(meterRegistry);
    }

    private record Work(Payment payment, UUID token) { }

    public Payment pay(UUID holderId, UUID holdId, long amount,
                       String idempotencyKey, String requestBody) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 80) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "Idempotency-Key는 1~80자여야 합니다.");
        }
        String requestHash = sha256Hex(requestBody);
        Work work = txTemplate.execute(status -> {
            // 같은 키가 서로 다른 hold로 동시에 들어오는 경우도 직렬화한다. TX 종료 시 자동 해제.
            jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))",
                    rs -> { return null; }, "payment-user:" + holderId);
            Optional<IdempotencyKey> existing = idempotencyKeyRepository.findById(
                    new IdempotencyKey.Id(holderId, idempotencyKey));
            if (existing.isPresent()) {
                IdempotencyKey key = existing.get();
                if (!key.getRequestHash().equals(requestHash)) {
                    throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                            "같은 Idempotency-Key로 다른 본문이 들어왔습니다.");
                }
                idempotentReplayCounter.increment();
                return new Work(paymentRepository.findById(key.getPaymentId()).orElseThrow(), null);
            }
            SeatHold hold = seatHoldRepository.findForUpdate(holdId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.SEAT_NOT_FOUND, "점유 정보를 찾을 수 없습니다."));
            if (!hold.getHolderId().equals(holderId)) {
                throw new BusinessException(ErrorCode.SHOW_ACCESS_DENIED, "본인의 점유만 결제할 수 있습니다.");
            }
            long expected = seatHoldViewService.calculateExpectedAmount(hold);
            if (expected != amount) {
                throw new BusinessException(ErrorCode.PAYMENT_AMOUNT_MISMATCH, "결제 금액이 일치하지 않습니다.");
            }
            // 새 키라도 처리 중인 같은 hold는 기존 결제로 연결한다. 실패 확정 후에만 새 결제 허용.
            Optional<Payment> active = paymentRepository.findByHoldId(holdId).stream()
                    .filter(p -> "PENDING".equals(p.getStatus()) || "APPROVED".equals(p.getStatus()))
                    .findFirst();
            if (active.isPresent()) {
                Payment p = active.get();
                idempotencyKeyRepository.save(IdempotencyKey.create(holderId, idempotencyKey, requestHash, p.getId()));
                return new Work(p, null);
            }
            if (!hold.isActive() || !hold.getExpiresAt().isAfter(now())) {
                throw new BusinessException(ErrorCode.PAYMENT_ALREADY_SETTLED, "만료되거나 종료된 점유는 결제할 수 없습니다.");
            }
            Payment p = Payment.start(holdId, holderId, hold.getScheduleId(), amount);
            UUID token = p.claim(now().plus(props.getProcessingLease()));
            paymentRepository.save(p);
            idempotencyKeyRepository.save(IdempotencyKey.create(holderId, idempotencyKey, requestHash, p.getId()));
            return new Work(p, token);
        });
        if (work.token() == null) return work.payment();
        long started = System.nanoTime();
        try {
            MockPgResponse result = mockPgClient.charge(work.payment().getId(), amount);
            return settle(work, new PgOrder(result.approved() ? "APPROVED" : "DECLINED",
                    result.pgTxnId(), result.code(), result.message()));
        } catch (PgUnavailableException ex) {
            log.warn("PG result unknown: paymentId={}", work.payment().getId());
            return defer(work);
        } finally {
            pgCallTimer.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    /** 다중 인스턴스가 같은 목록을 읽어도 payment 행 잠금과 처리 토큰으로 작업권을 획득한다. */
    public void reconcileDue() {
        if (!props.isReconcileEnabled()) return;
        for (UUID id : paymentRepository.findDue(now(), PageRequest.of(0, props.getReconcileBatchSize()))) {
            try {
                reconcile(id);
            } catch (Exception ex) {
                // 확정 TX 자체가 실패해도 lease 만료 뒤 다시 조회한다. 다른 결제의 복구는 계속한다.
                log.warn("Payment reconciliation deferred: paymentId={}, cause={}", id, ex.toString());
            }
        }
    }

    public void reconcile(UUID id) {
        Work work = txTemplate.execute(status -> {
            Payment p = paymentRepository.findForUpdate(id).orElse(null);
            if (p == null || !"PENDING".equals(p.getStatus()) || p.getReconcileAt().isAfter(now())) return null;
            return new Work(p, p.claim(now().plus(props.getProcessingLease())));
        });
        if (work == null) return;
        try {
            if (work.payment().isCancelRequested()) {
                completeCancellation(work);
            } else {
                settle(work, mockPgClient.lookup(id));
            }
        } catch (PgUnavailableException ex) {
            log.warn("PG reconciliation unavailable: paymentId={}", id);
            defer(work);
        }
    }

    private Payment settle(Work work, PgOrder result) {
        Payment decided = txTemplate.execute(status -> {
            Payment p = paymentRepository.findForUpdate(work.payment().getId()).orElseThrow();
            if (!p.owns(work.token())) return p;
            if (p.isCancelRequested()) return p;
            if ("NOT_FOUND".equals(result.status())) {
                // 늦게 도착한 최초 /pay도 승인되지 않도록 먼저 PG 취소 tombstone을 만든다.
                p.requestCancellation();
            } else if ("APPROVED".equals(result.status())) {
                SeatHold hold = seatHoldRepository.findForUpdate(p.getHoldId()).orElse(null);
                if (hold == null || !hold.isActive() || !hold.getExpiresAt().isAfter(now())) {
                    // 취소 의도를 커밋한 뒤 HTTP 호출. 이후 worker는 승인 대신 취소만 수행한다.
                    p.requestCancellation();
                } else {
                    seatHoldService.markSold(p.getHoldId(), p.getHolderId());
                    p.approve(result.approveNo());
                    outboxWriter.stage(AGGREGATE_PAYMENT, p.getId(), "PAYMENT_APPROVED", Topics.PAYMENT_APPROVED,
                            PaymentEventPayloads.approved(objectMapper, p), PaymentEventPayloads.VERSION_V1);
                    approvedCounter.increment();
                }
            } else if ("DECLINED".equals(result.status()) || "CANCELLED".equals(result.status())) {
                fail(p, "CANCELLED".equals(result.status()) ? "PG_CANCELLED" : "PG_DECLINED",
                        "CANCELLED".equals(result.status()) ? "PG 취소 확인" : "PG 결제 거절");
            } else {
                SeatHold hold = seatHoldRepository.findForUpdate(p.getHoldId()).orElse(null);
                if (hold == null || !hold.isActive() || !hold.getExpiresAt().isAfter(now())) p.requestCancellation();
                else p.retryAt(now().plus(props.getReconcileRetry()));
            }
            return p;
        });
        if (decided.owns(work.token()) && decided.isCancelRequested()) return completeCancellation(work);
        return decided;
    }

    private Payment completeCancellation(Work work) {
        mockPgClient.cancel(work.payment().getId());
        return txTemplate.execute(status -> {
            Payment p = paymentRepository.findForUpdate(work.payment().getId()).orElseThrow();
            if (p.owns(work.token()) && p.isCancelRequested()) fail(p, "PG_CANCELLED", "점유 종료 또는 미접수 결제의 PG 취소 확인");
            return p;
        });
    }

    private void fail(Payment p, String code, String reason) {
        p.fail(code, reason);
        outboxWriter.stage(AGGREGATE_PAYMENT, p.getId(), "PAYMENT_FAILED", Topics.PAYMENT_FAILED,
                PaymentEventPayloads.failed(objectMapper, p), PaymentEventPayloads.VERSION_V1);
        failedCounter.increment();
    }

    private Payment defer(Work work) {
        return txTemplate.execute(status -> {
            Payment p = paymentRepository.findForUpdate(work.payment().getId()).orElseThrow();
            if (p.owns(work.token())) p.retryAt(now().plus(props.getReconcileRetry()));
            return p;
        });
    }

    private OffsetDateTime now() { return OffsetDateTime.now(clock); }

    /** 본인이 시작한 결제 단건 조회. */
    public Payment getMyPayment(UUID paymentId, UUID requesterId) {
        Payment p = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST,
                        "결제 정보를 찾을 수 없습니다. paymentId=" + paymentId));
        if (!p.getHolderId().equals(requesterId)) {
            throw new BusinessException(ErrorCode.SHOW_ACCESS_DENIED,
                    "본인의 결제만 조회할 수 있습니다.");
        }
        return p;
    }

    /** 본인의 결제 이력. */
    public List<Payment> listMine(UUID holderId) {
        return paymentRepository.findByHolderIdOrderByCreatedAtDesc(holderId);
    }

    /**
     * 결제 이력 + 좌석/구역 메타 보강 — /me/tickets 페이지가 한 번의 호출로 화면을 채울 수 있게.
     *
     * <p>
     *   각 payment 마다 holdId 로 SeatHold 조회 + {@link SeatHoldViewService#enrich} 호출.
     *   결제 건수는 통상 적기 때문에 N+1 영향 작음 — 페이지 표시 빈도가 높지 않으므로
     *   batch 조회 최적화는 후속.
     * </p>
     */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public List<com.ticketing.paymentsaga.api.dto.PaymentDetailResponse> listMyDetails(UUID holderId) {
        List<Payment> payments = paymentRepository.findByHolderIdOrderByCreatedAtDesc(holderId);
        List<com.ticketing.paymentsaga.api.dto.PaymentDetailResponse> result =
                new java.util.ArrayList<>(payments.size());
        for (Payment p : payments) {
            com.ticketing.paymentsaga.api.dto.PaymentDetailResponse base =
                    com.ticketing.paymentsaga.api.dto.PaymentDetailResponse.fromPayment(p);
            // SeatHold lookup → enrich. 이론상 hold 가 없을 일은 없지만, 보강 실패 시 base 만 반환.
            var holdOpt = seatHoldRepository.findById(p.getHoldId());
            if (holdOpt.isPresent()) {
                com.ticketing.seat.api.dto.SeatHoldResponse enriched =
                        seatHoldViewService.enrich(holdOpt.get());
                result.add(base.withHoldEnriched(
                        enriched.showId(),
                        enriched.seatIds(),
                        enriched.sectionBreakdown()));
            } else {
                result.add(base);
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 은 JDK 표준 — 도달 불가.
            throw new IllegalStateException(e);
        }
    }
}
