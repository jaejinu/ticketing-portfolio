package com.ticketing.paymentsaga;

import com.ticketing.auth.domain.*;
import com.ticketing.common.error.BusinessException;
import com.ticketing.paymentsaga.application.*;
import com.ticketing.paymentsaga.domain.*;
import com.ticketing.seat.application.SeatHoldService;
import com.ticketing.seat.domain.*;
import com.ticketing.show.domain.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(classes = PaymentIntegrationTestApp.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@Timeout(30)
class PaymentRecoveryIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }
    @Autowired PaymentSagaService saga;
    @Autowired MockPgClient pg;
    @Autowired JdbcTemplate jdbc;
    @Autowired PaymentRepository payments;
    @Autowired SeatHoldRepository holds;
    @Autowired SeatRepository seats;
    @Autowired SectionRepository sections;
    @Autowired ShowRepository shows;
    @Autowired ShowScheduleRepository schedules;
    @Autowired UserRepository users;
    @Autowired SeatHoldService seatService;
    @Autowired PaymentRefundService refunds;

    UUID holder;
    SeatHold hold;
    ExecutorService executor;

    @BeforeEach
    void seed() {
        reset(pg);
        jdbc.execute("TRUNCATE admin_audit_log, idempotency_keys, payments, outbox_events, seat_hold_prices, seat_hold_items, seat_holds, seats, sections, show_schedules, shows, users CASCADE");
        User user = users.save(User.newUser("payment-it@example.com", "test-hash", "Payment IT"));
        holder = user.getId();
        Show show = shows.save(Show.createWithId(UUID.randomUUID(), holder, "Payment IT", "Hall", null, null, ShowStatus.PUBLISHED));
        OffsetDateTime now = OffsetDateTime.now();
        ShowSchedule schedule = schedules.save(ShowSchedule.createWithId(UUID.randomUUID(), show.getId(), now.plusDays(7), now.plusDays(7).plusHours(2), now.minusDays(1), ShowScheduleStatus.ON_SALE));
        Section section = sections.save(Section.create(schedule.getId(), "VIP", SectionGrade.VIP, 100000));
        Seat seat = Seat.create(section.getId(), "A", 1);
        seat.hold();
        seats.save(seat);
        hold = holds.save(SeatHold.create(schedule.getId(), holder, List.of(seat.getId()),
                Map.of(section.getId(), 100000L), 100000, now, now.plusMinutes(5)));
        when(pg.charge(any(), anyLong())).thenReturn(new MockPgResponse(true, "AP-1", "APPROVED", null));
        when(pg.lookup(any())).thenReturn(new PgOrder("APPROVED", "AP-1", "APPROVED", null));
        when(pg.cancel(any())).thenReturn(new PgOrder("CANCELLED", null, "CANCELLED", null));
        executor = Executors.newFixedThreadPool(8);
    }
    @AfterEach
    void stop() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
    Payment pay(String key) { return saga.pay(holder, hold.getId(), 100000, key, hold.getId() + ":100000"); }
    void due(UUID id) { jdbc.update("UPDATE payments SET reconcile_at = now() - interval '1 second' WHERE id = ?", id); }
    Payment reload(UUID id) { return payments.findById(id).orElseThrow(); }
    long events(String type) { return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = ?", Long.class, type); }
    Payment unknown() {
        when(pg.charge(any(), anyLong())).thenThrow(new PgUnavailableException("timeout", null));
        Payment payment = pay("first");
        assertThat(payment.getStatus()).isEqualTo("PENDING");
        return payment;
    }

    @Test
    void concurrentSameAndDifferentKeysShareOneCharge() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(pg.charge(any(), anyLong())).thenAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return new MockPgResponse(true, "AP-1", "APPROVED", null);
        });
        Future<Payment> first = executor.submit(() -> pay("same"));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            List<Future<Payment>> others = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                String key = i % 2 == 0 ? "same" : "other-" + i;
                others.add(executor.submit(() -> pay(key)));
            }
            UUID id = null;
            for (Future<Payment> future : others) {
                Payment p = future.get(5, TimeUnit.SECONDS);
                assertThat(p.getStatus()).isEqualTo("PENDING");
                if (id == null) id = p.getId();
                assertThat(p.getId()).isEqualTo(id);
            }
            assertThat(payments.count()).isEqualTo(1);
            verify(pg, times(1)).charge(any(), anyLong());
        } finally { release.countDown(); }
        assertThat(first.get(5, TimeUnit.SECONDS).getStatus()).isEqualTo("APPROVED");
        assertThat(events("PAYMENT_APPROVED")).isEqualTo(1);
        assertThat(events("SEAT_SOLD")).isEqualTo(1);
    }

    @Test
    void timeoutThenLookupRecoversWithoutChargingAgain() {
        Payment p = unknown();
        assertThat(pay("first").getId()).isEqualTo(p.getId());
        due(p.getId());
        saga.reconcileDue();
        assertThat(reload(p.getId()).getStatus()).isEqualTo("APPROVED");
        assertThat(holds.findById(hold.getId()).orElseThrow().getStatus()).isEqualTo("SOLD");
        verify(pg, times(1)).charge(any(), anyLong());
        assertThat(events("PAYMENT_APPROVED")).isEqualTo(1);
    }

    @Test
    void concurrentReconcilersPublishOnlyOnce() throws Exception {
        Payment p = unknown();
        due(p.getId());
        List<Callable<Void>> jobs = new ArrayList<>();
        for (int i = 0; i < 8; i++) jobs.add(() -> { saga.reconcile(p.getId()); return null; });
        for (Future<Void> f : executor.invokeAll(jobs)) f.get();
        verify(pg, times(1)).lookup(p.getId());
        assertThat(events("PAYMENT_APPROVED")).isEqualTo(1);
        assertThat(events("SEAT_SOLD")).isEqualTo(1);
    }

    @Test
    void expiredApprovedPaymentIsCancelledBeforeFailing() {
        Payment p = unknown();
        jdbc.update("UPDATE seat_holds SET expires_at = now() - interval '1 second' WHERE id = ?", hold.getId());
        seatService.expireDueHolds(10);
        due(p.getId());
        saga.reconcile(p.getId());
        assertThat(reload(p.getId()).getStatus()).isEqualTo("FAILED");
        assertThat(reload(p.getId()).getFailCode()).isEqualTo("PG_CANCELLED");
        verify(pg).cancel(p.getId());
        assertThat(seats.findById(hold.getSeatIds().iterator().next()).orElseThrow().getStatus()).isEqualTo("AVAILABLE");
        assertThat(events("PAYMENT_APPROVED")).isZero();
    }

    @Test
    void failedCancellationRetainsIntentAndRetriesCancellationOnly() {
        Payment p = unknown();
        seatService.release(hold.getId(), holder);
        when(pg.cancel(p.getId())).thenThrow(new PgUnavailableException("cancel timeout", null))
                .thenReturn(new PgOrder("CANCELLED", null, null, null));
        due(p.getId());
        saga.reconcile(p.getId());
        assertThat(reload(p.getId()).getStatus()).isEqualTo("PENDING");
        assertThat(reload(p.getId()).isCancelRequested()).isTrue();
        due(p.getId());
        saga.reconcile(p.getId());
        assertThat(reload(p.getId()).getStatus()).isEqualTo("FAILED");
        verify(pg, times(1)).lookup(p.getId());
        verify(pg, times(2)).cancel(p.getId());
        assertThat(events("PAYMENT_FAILED")).isEqualTo(1);
    }

    @Test
    void missingPgOrderRequiresConfirmedCancellation() {
        Payment p = unknown();
        when(pg.lookup(p.getId())).thenReturn(new PgOrder("NOT_FOUND", null, null, null));
        due(p.getId());
        saga.reconcile(p.getId());
        verify(pg).cancel(p.getId());
        assertThat(reload(p.getId()).getFailCode()).isEqualTo("PG_CANCELLED");
    }

    @Test
    void lookupFailureDoesNotReleaseOrRecharge() {
        Payment p = unknown();
        when(pg.lookup(p.getId())).thenThrow(new PgUnavailableException("offline", null));
        due(p.getId());
        saga.reconcile(p.getId());
        assertThat(reload(p.getId()).getStatus()).isEqualTo("PENDING");
        assertThat(reload(p.getId()).getReconcileAt()).isAfter(OffsetDateTime.now());
        verify(pg, never()).cancel(any());
        verify(pg, times(1)).charge(any(), anyLong());
        assertThat(events("PAYMENT_FAILED")).isZero();
    }

    @Test
    void expiredLeaseFencesLateInitialApproval() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(pg.charge(any(), anyLong())).thenAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return new MockPgResponse(true, "AP-LATE", "APPROVED", null);
        });
        Future<Payment> first = executor.submit(() -> pay("first"));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            Payment p = payments.findAll().get(0);
            due(p.getId());
            when(pg.lookup(p.getId())).thenReturn(new PgOrder("NOT_FOUND", null, null, null));
            saga.reconcile(p.getId());
            assertThat(reload(p.getId()).getStatus()).isEqualTo("FAILED");
        } finally { release.countDown(); }
        assertThat(first.get(5, TimeUnit.SECONDS).getStatus()).isEqualTo("FAILED");
        assertThat(events("PAYMENT_APPROVED")).isZero();
    }

    @Test
    void declineAllowsNewKeyButSameKeyRemainsFailed() {
        when(pg.charge(any(), anyLong())).thenReturn(new MockPgResponse(false, null, "LIMIT_EXCEEDED", "declined"));
        Payment failed = pay("declined");
        assertThat(failed.getStatus()).isEqualTo("FAILED");
        assertThat(pay("declined").getId()).isEqualTo(failed.getId());
        when(pg.charge(any(), anyLong())).thenReturn(new MockPgResponse(true, "AP-2", "APPROVED", null));
        assertThat(pay("retry").getStatus()).isEqualTo("APPROVED");
        assertThat(payments.count()).isEqualTo(2);
    }

    @Test
    void pgApprovalSurvivesFinalDatabaseTransactionFailure() {
        jdbc.execute("CREATE FUNCTION reject_payment_approval() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.status = 'APPROVED' THEN RAISE EXCEPTION 'simulated crash before commit'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER reject_approval BEFORE UPDATE ON payments FOR EACH ROW EXECUTE FUNCTION reject_payment_approval()");
        UUID id;
        try {
            assertThatThrownBy(() -> pay("crash")).isInstanceOf(RuntimeException.class);
            Payment p = payments.findAll().get(0);
            id = p.getId();
            assertThat(p.getStatus()).isEqualTo("PENDING");
            assertThat(holds.findById(hold.getId()).orElseThrow().getStatus()).isEqualTo("ACTIVE");
            assertThat(events("PAYMENT_APPROVED")).isZero();
            assertThat(events("SEAT_SOLD")).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER reject_approval ON payments");
            jdbc.execute("DROP FUNCTION reject_payment_approval()");
        }
        due(id);
        saga.reconcile(id);
        assertThat(reload(id).getStatus()).isEqualTo("APPROVED");
        verify(pg, times(1)).charge(any(), anyLong());
        assertThat(events("PAYMENT_APPROVED")).isEqualTo(1);
    }

    @Test
    void pendingPgOutcomeDefersUntilHoldExpiresThenCancels() {
        Payment p = unknown();
        when(pg.lookup(p.getId())).thenReturn(new PgOrder("PENDING", null, null, null));
        due(p.getId());
        saga.reconcile(p.getId());
        assertThat(reload(p.getId()).getStatus()).isEqualTo("PENDING");
        verify(pg, never()).cancel(any());
        jdbc.update("UPDATE seat_holds SET expires_at = now() - interval '1 second' WHERE id = ?", hold.getId());
        due(p.getId());
        saga.reconcile(p.getId());
        verify(pg).cancel(p.getId());
        assertThat(reload(p.getId()).getStatus()).isEqualTo("FAILED");
    }

    @Test
    void keyConflictAndExpiredHoldNeverCharge() {
        unknown();
        assertThatThrownBy(() -> saga.pay(holder, UUID.randomUUID(), 100000, "first", "different"))
                .isInstanceOf(BusinessException.class);
        jdbc.execute("TRUNCATE idempotency_keys, payments CASCADE");
        jdbc.update("UPDATE seat_holds SET expires_at = now() - interval '1 second' WHERE id = ?", hold.getId());
        clearInvocations(pg);
        assertThatThrownBy(() -> pay("expired")).isInstanceOf(BusinessException.class);
        verify(pg, never()).charge(any(), anyLong());
    }

    @Test
    void refundReturnsTheSeatAndIsIdempotent() {
        Payment approved = pay("refund");
        assertThat(approved.getStatus()).isEqualTo("APPROVED");
        UUID admin = UUID.randomUUID();

        Payment refunded = refunds.refund(admin, approved.getId(), "공연 취소");
        assertThat(refunded.getStatus()).isEqualTo("REFUNDED");
        verify(pg).cancel(approved.getId());
        assertThat(seats.findById(hold.getSeatIds().iterator().next()).orElseThrow().getStatus()).isEqualTo("AVAILABLE");
        assertThat(holds.findById(hold.getId()).orElseThrow().getStatus()).isEqualTo("RELEASED");
        assertThat(events("PAYMENT_REFUNDED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_audit_log WHERE action = 'PAYMENT_REFUNDED' AND actor_id = ?",
                Long.class, admin)).isEqualTo(1);

        clearInvocations(pg);
        assertThat(refunds.refund(admin, approved.getId(), "again").getStatus()).isEqualTo("REFUNDED");
        verify(pg, never()).cancel(any());
        assertThat(events("PAYMENT_REFUNDED")).isEqualTo(1);
    }

    @Test
    void failedRefundPersistsIntentAndRecoversWithoutAnotherAdminRequest() {
        Payment approved = pay("refund-pg-down");
        UUID admin = UUID.randomUUID();
        when(pg.cancel(any())).thenThrow(new PgUnavailableException("down", null));
        assertThatThrownBy(() -> refunds.refund(admin, approved.getId(), "취소"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(com.ticketing.common.error.ErrorCode.PAYMENT_PG_FAILURE));
        assertThat(reload(approved.getId()).getStatus()).isEqualTo("APPROVED");
        assertThat(reload(approved.getId()).isRefundPending()).isTrue();
        assertThat(saga.listMyDetails(holder).getFirst().refundPending()).isTrue();
        assertThat(saga.listMyDetails(holder).getFirst().seatIds()).isNotEmpty();
        assertThat(reload(approved.getId()).getRefundActorId()).isEqualTo(admin);
        assertThat(seats.findById(hold.getSeatIds().iterator().next()).orElseThrow().getStatus()).isEqualTo("SOLD");
        assertThat(events("PAYMENT_REFUNDED")).isZero();

        // A retry before the lease expires must not call the PG or replace the audit actor.
        refunds.refund(UUID.randomUUID(), approved.getId(), "retry");
        verify(pg, times(1)).cancel(approved.getId());
        doReturn(new PgOrder("CANCELLED", null, "CANCELLED", null)).when(pg).cancel(any());
        due(approved.getId());
        refunds.reconcileDue();
        refunds.reconcileDue();
        assertThat(reload(approved.getId()).getStatus()).isEqualTo("REFUNDED");
        assertThat(reload(approved.getId()).isRefundPending()).isFalse();
        assertThat(saga.listMyDetails(holder).getFirst().refundPending()).isFalse();
        assertThat(reload(approved.getId()).getRefundActorId()).isEqualTo(admin);
        assertThat(reload(approved.getId()).getRefundReason()).isEqualTo("취소");
        assertThat(seats.findById(hold.getSeatIds().iterator().next()).orElseThrow().getStatus()).isEqualTo("AVAILABLE");
        assertThat(events("PAYMENT_REFUNDED")).isEqualTo(1);
        verify(pg, times(2)).cancel(approved.getId());
    }

    @Test
    void refundRecoversWhenPgCancelledButDatabaseCommitFailed() {
        Payment approved = pay("refund-commit-failure");
        jdbc.execute("CREATE FUNCTION reject_refund() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.status = 'REFUNDED' THEN RAISE EXCEPTION 'simulated crash'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER reject_refund BEFORE UPDATE ON payments FOR EACH ROW EXECUTE FUNCTION reject_refund()");
        try {
            assertThatThrownBy(() -> refunds.refund(UUID.randomUUID(), approved.getId(), "cancel"))
                    .isInstanceOf(RuntimeException.class);
            assertThat(reload(approved.getId()).isRefundPending()).isTrue();
            assertThat(events("PAYMENT_REFUNDED")).isZero();
            assertThat(seats.findById(hold.getSeatIds().iterator().next()).orElseThrow().getStatus()).isEqualTo("SOLD");
        } finally {
            jdbc.execute("DROP TRIGGER reject_refund ON payments");
            jdbc.execute("DROP FUNCTION reject_refund()");
        }
        due(approved.getId());
        refunds.reconcileDue();
        assertThat(reload(approved.getId()).getStatus()).isEqualTo("REFUNDED");
        assertThat(events("PAYMENT_REFUNDED")).isEqualTo(1);
        assertThat(seats.findById(hold.getSeatIds().iterator().next()).orElseThrow().getStatus()).isEqualTo("AVAILABLE");
        verify(pg, times(2)).cancel(approved.getId());
    }

    @Test
    void lateRefundWorkerCannotPublishAgainAfterRecoveryTakesItsLease() throws Exception {
        Payment approved = pay("concurrent-refund");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        when(pg.cancel(approved.getId())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return new PgOrder("CANCELLED", null, "CANCELLED", null);
        });
        UUID actor = UUID.randomUUID();
        Future<Payment> first = executor.submit(() -> refunds.refund(actor, approved.getId(), "original"));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            Payment duplicate = refunds.refund(UUID.randomUUID(), approved.getId(), "duplicate");
            assertThat(duplicate.isRefundPending()).isTrue();
            assertThat(calls.get()).isEqualTo(1);
            due(approved.getId());
            refunds.reconcileDue();
            assertThat(reload(approved.getId()).getStatus()).isEqualTo("REFUNDED");
        } finally { release.countDown(); }
        assertThat(first.get(5, TimeUnit.SECONDS).getStatus()).isEqualTo("REFUNDED");
        assertThat(events("PAYMENT_REFUNDED")).isEqualTo(1);
        assertThat(reload(approved.getId()).getRefundActorId()).isEqualTo(actor);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_audit_log WHERE action='PAYMENT_REFUNDED'", Long.class)).isEqualTo(1);
    }

    @Test
    void scheduleClosedWhilePgOutcomeUnknownCancelsInsteadOfSelling() {
        Payment pending = unknown();
        jdbc.update("UPDATE show_schedules SET status='CLOSED' WHERE id=?", hold.getScheduleId());
        due(pending.getId());
        saga.reconcile(pending.getId());
        assertThat(reload(pending.getId()).getStatus()).isEqualTo("FAILED");
        verify(pg).cancel(pending.getId());
        assertThat(events("PAYMENT_APPROVED")).isZero();
        assertThat(events("SEAT_SOLD")).isZero();
        assertThat(seats.findById(hold.getSeatIds().iterator().next()).orElseThrow().getStatus()).isEqualTo("HELD");
    }

    @Test
    void scheduleRefundRequiresAClosedSchedule() {
        Payment approved = pay("schedule-refund");
        assertThatThrownBy(() -> refunds.refundSchedule(UUID.randomUUID(), hold.getScheduleId(), null))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(com.ticketing.common.error.ErrorCode.INVALID_SHOW_STATE));

        jdbc.update("UPDATE show_schedules SET status = 'CLOSED' WHERE id = ?", hold.getScheduleId());
        var batch = refunds.refundSchedule(UUID.randomUUID(), hold.getScheduleId(), "공연 취소");
        assertThat(batch.refunded()).containsExactly(approved.getId());
        assertThat(batch.failed()).isEmpty();
        assertThat(reload(approved.getId()).getStatus()).isEqualTo("REFUNDED");
    }

    @Test
    void scheduleClosedAfterHoldIsNeverCharged() {
        // 점유 뒤 조직자가 회차를 닫은 경우 — PG 를 부르기 전에 판매 자격을 다시 본다.
        jdbc.update("UPDATE show_schedules SET status = 'CLOSED' WHERE id = ?", hold.getScheduleId());
        assertThatThrownBy(() -> pay("closed"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(com.ticketing.common.error.ErrorCode.SALES_NOT_OPEN));
        verify(pg, never()).charge(any(), anyLong());
        assertThat(payments.count()).isZero();
    }

    @Test
    void databaseRejectsSecondActivePaymentForSameHoldAndUnknownReferences() {
        // 앱의 잠금을 거치지 않은 경로(운영 스크립트 등)에서도 이중 청구가 DB 에서 막힌다 (V014).
        Payment approved = pay("first");
        String insert = "INSERT INTO payments (id, hold_id, holder_id, schedule_id, amount, status) VALUES (?, ?, ?, ?, 100000, ?)";
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), hold.getId(), holder, hold.getScheduleId(), "PENDING"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        // 실패한 결제 기록은 몇 건이든 남길 수 있다.
        jdbc.update(insert, UUID.randomUUID(), hold.getId(), holder, hold.getScheduleId(), "FAILED");
        // 없는 점유·회차를 가리키는 결제는 만들 수 없다.
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), UUID.randomUUID(), holder, hold.getScheduleId(), "FAILED"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), hold.getId(), holder, UUID.randomUUID(), "FAILED"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(reload(approved.getId()).getStatus()).isEqualTo("APPROVED");
    }

    @Test
    void oldIdempotencyKeysArePurgedButPendingOnesStay() {
        Payment pending = unknown(); // 결과 불명 — PENDING 으로 남는다
        UUID failed = UUID.randomUUID();
        jdbc.update("INSERT INTO payments (id, hold_id, holder_id, schedule_id, amount, status) VALUES (?, ?, ?, ?, 100000, 'FAILED')",
                failed, hold.getId(), holder, hold.getScheduleId());
        jdbc.update("INSERT INTO idempotency_keys (holder_id, idempotency_key, request_hash, payment_id) VALUES (?, 'old', 'h', ?)",
                holder, failed);
        jdbc.update("UPDATE idempotency_keys SET created_at = now() - interval '8 days'");

        assertThat(new IdempotencyKeyPurger(jdbc, new PaymentProperties()).purgeOnce()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_keys WHERE payment_id = ?", Long.class, failed))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_keys WHERE payment_id = ?", Long.class, pending.getId()))
                .isEqualTo(1);
    }
}
