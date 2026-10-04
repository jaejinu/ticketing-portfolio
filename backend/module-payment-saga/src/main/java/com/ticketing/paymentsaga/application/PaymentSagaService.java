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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 결제 Saga 오케스트레이션.
 *
 * <h2>핵심 흐름</h2>
 * <pre>
 *   pay(holdId, amount, idempotencyKey, body, holderId):
 *     ─ TX 1: 멱등 키 체크 + Payment 생성 ──────────────────────────
 *       1) (holderId, key) 가 이미 있으면:
 *            hash 일치 → 기존 Payment 반환 (멱등 응답)
 *            hash 불일치 → IDEMPOTENCY_KEY_CONFLICT
 *       2) Payment.start() 영속 (PENDING)
 *       3) IdempotencyKey 영속
 *     COMMIT
 *
 *     ─ 외부: Mock PG 호출 (TX 밖) ────────────────────────────────
 *       4) mockPgClient.charge(paymentId, amount)
 *
 *     ─ TX 2: finalize ───────────────────────────────────────────
 *       5-A) PG 승인:
 *            seatHoldService.markSold(holdId, holderId)  → SeatHold + Seat SOLD + SEAT_SOLD outbox
 *            payment.approve(pgTxnId)
 *            outbox PAYMENT_APPROVED
 *       5-B) PG 거절 또는 PG 호출 실패:
 *            payment.fail(code, reason)
 *            outbox PAYMENT_FAILED
 *            (hold 는 그대로 — 사용자가 다른 결제 수단으로 재시도 가능)
 *     COMMIT
 *
 *     return Payment
 * </pre>
 *
 * <h2>왜 TX 경계를 둘로 나누는가</h2>
 * <p>
 *   첫 TX 안에서 PG 호출까지 묶으면 PG 응답 대기 시간 동안 DB 락이 유지된다.
 *   PG 가 느리면 동시 결제가 줄줄이 막힌다. 따라서 stage(첫 TX) → PG → finalize(둘째 TX) 패턴.
 * </p>
 *
 * <h2>멱등 재진입</h2>
 * <p>
 *   같은 (holderId, idempotencyKey) 가 다시 오면 첫 TX 가 기존 Payment 를 그대로 반환하고
 *   PG 를 또 호출하지 않는다. 첫 호출이 PENDING 으로 남아 있는 경우(서버 다운 등) 는 Phase 5b
 *   reconciler 가 PG 조회 후 결정.
 * </p>
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
                               MeterRegistry meterRegistry) {
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

        this.approvedCounter = Counter.builder("payment.approved")
                .description("Payments approved by mock PG")
                .register(meterRegistry);
        this.failedCounter = Counter.builder("payment.failed")
                .description("Payments rejected by mock PG or PG-unavailable")
                .register(meterRegistry);
        this.idempotentReplayCounter = Counter.builder("payment.idempotent.replay")
                .description("Idempotency-Key replays returning existing payment")
                .register(meterRegistry);
        this.pgCallTimer = Timer.builder("payment.pg.call.duration")
                .description("Mock PG call latency")
                .register(meterRegistry);
    }

    /**
     * 결제 진행 — saga 전체 진입점.
     *
     * @param holderId       인증된 사용자 id
     * @param holdId         점유 식별자
     * @param amount         결제 금액 (원)
     * @param idempotencyKey 클라이언트가 발급한 멱등 키 (Idempotency-Key 헤더)
     * @param requestBody    멱등 hash 계산용 — 클라이언트가 보낸 body 원본
     * @return 결제 결과 Payment (APPROVED 또는 FAILED)
     */
    public Payment pay(UUID holderId, UUID holdId, long amount,
                        String idempotencyKey, String requestBody) {

        // ---- TX 1: 멱등 + SeatHold 검증 + amount 검증 + Payment 생성 -------------
        String requestHash = sha256Hex(requestBody);
        Payment payment = txTemplate.execute(status -> {
            Optional<IdempotencyKey> existing = idempotencyKeyRepository.findById(
                    new IdempotencyKey.Id(holderId, idempotencyKey));
            if (existing.isPresent()) {
                IdempotencyKey ik = existing.get();
                if (!ik.getRequestHash().equals(requestHash)) {
                    throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                            "같은 Idempotency-Key 로 다른 본문이 들어왔습니다.");
                }
                idempotentReplayCounter.increment();
                return paymentRepository.findById(ik.getPaymentId())
                        .orElseThrow(() -> new IllegalStateException(
                                "idempotency_keys 가 가리키는 payment 가 없음. paymentId=" + ik.getPaymentId()));
            }

            // ---- SeatHold 조회 + 본인/상태 검증 -----------------------------------
            SeatHold hold = seatHoldRepository.findById(holdId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.SEAT_NOT_FOUND,
                            "점유 정보를 찾을 수 없습니다. holdId=" + holdId));
            if (!hold.getHolderId().equals(holderId)) {
                throw new BusinessException(ErrorCode.SHOW_ACCESS_DENIED,
                        "본인의 점유만 결제할 수 있습니다.");
            }
            if (!hold.isActive()) {
                throw new BusinessException(ErrorCode.PAYMENT_ALREADY_SETTLED,
                        "ACTIVE 가 아닌 점유는 결제할 수 없습니다. status=" + hold.getStatus());
            }

            // ---- amount 서버 truth 검증 -----------------------------------------
            // 클라이언트가 보낸 amount 와 SeatHold 좌석의 section basePrice 합이 일치해야 함.
            // 위변조 + 가격 변동 사이 race + sessionStorage 깨짐 등을 한곳에서 차단.
            long expected = seatHoldViewService.calculateExpectedAmount(hold);
            if (expected != amount) {
                throw new BusinessException(ErrorCode.PAYMENT_AMOUNT_MISMATCH,
                        "결제 금액 불일치. expected=" + expected + ", actual=" + amount);
            }

            // ---- Payment 영속 ----------------------------------------------------
            Payment p = Payment.start(holdId, holderId, hold.getScheduleId(), amount);
            paymentRepository.save(p);
            idempotencyKeyRepository.save(
                    IdempotencyKey.create(holderId, idempotencyKey, requestHash, p.getId()));
            return p;
        });

        // 멱등 응답: 이미 처리된 결제는 그대로 반환 (PG 호출 안 함).
        if (!"PENDING".equals(payment.getStatus())) {
            log.debug("idempotent replay returning payment id={}, status={}",
                    payment.getId(), payment.getStatus());
            return payment;
        }

        // ---- 외부: PG 호출 (TX 밖) --------------------------------------------
        MockPgResponse pgResponse;
        boolean pgUnavailable = false;
        long startNanos = System.nanoTime();
        try {
            pgResponse = mockPgClient.charge(payment.getId(), payment.getAmount());
            pgCallTimer.record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
        } catch (PgUnavailableException ex) {
            pgCallTimer.record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
            log.warn("PG unavailable for payment id={}: {}", payment.getId(), ex.getMessage());
            pgResponse = null;
            pgUnavailable = true;
        }

        // ---- TX 2: finalize ---------------------------------------------------
        final MockPgResponse finalPgResponse = pgResponse;
        final boolean finalPgUnavailable = pgUnavailable;
        final UUID paymentId = payment.getId();
        return txTemplate.execute(status -> {
            Payment p = paymentRepository.findById(paymentId).orElseThrow();
            if (!"PENDING".equals(p.getStatus())) {
                // 누군가 이미 finalize 했음 (멀티 스레드 동시 진입 — 사실상 없음). 그대로 반환.
                return p;
            }

            if (finalPgUnavailable) {
                // PG 호출 자체 실패. 정책: FAILED 로 즉시 마킹 + outbox.
                if (!props.isFailOnPgError()) {
                    // 정책이 PENDING 유지인 경우엔 그대로 둠. reconciler 가 처리.
                    return p;
                }
                p.fail("PG_UNAVAILABLE", "PG 호출 실패 — 일시 장애 또는 네트워크 오류");
                outboxWriter.stage(AGGREGATE_PAYMENT, p.getId(),
                        "PAYMENT_FAILED", Topics.PAYMENT_FAILED,
                        PaymentEventPayloads.failed(objectMapper, p),
                        PaymentEventPayloads.VERSION_V1);
                failedCounter.increment();
                return p;
            }

            if (finalPgResponse.approved()) {
                // ---- 승인 흐름 -----------------------------------------------
                // SeatHold + Seat SOLD 전이는 SeatHoldService 가 책임 + 자체 outbox SEAT_SOLD.
                seatHoldService.markSold(p.getHoldId(), p.getHolderId());
                p.approve(finalPgResponse.pgTxnId());
                outboxWriter.stage(AGGREGATE_PAYMENT, p.getId(),
                        "PAYMENT_APPROVED", Topics.PAYMENT_APPROVED,
                        PaymentEventPayloads.approved(objectMapper, p),
                        PaymentEventPayloads.VERSION_V1);
                approvedCounter.increment();
                log.debug("payment APPROVED: id={}, pgTxn={}", p.getId(), p.getPgTxnId());
                return p;
            }

            // ---- 거절 흐름 ---------------------------------------------------
            p.fail(finalPgResponse.code() != null ? finalPgResponse.code() : "PG_DECLINED",
                    finalPgResponse.message());
            outboxWriter.stage(AGGREGATE_PAYMENT, p.getId(),
                    "PAYMENT_FAILED", Topics.PAYMENT_FAILED,
                    PaymentEventPayloads.failed(objectMapper, p),
                    PaymentEventPayloads.VERSION_V1);
            failedCounter.increment();
            log.debug("payment FAILED: id={}, code={}", p.getId(), p.getFailCode());
            return p;
        });
    }

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
