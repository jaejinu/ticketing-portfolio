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

    UUID holder;
    SeatHold hold;
    ExecutorService executor;

    @BeforeEach
    void seed() {
        reset(pg);
        jdbc.execute("TRUNCATE idempotency_keys, payments, outbox_events, seat_hold_prices, seat_hold_items, seat_holds, seats, sections, show_schedules, shows, users CASCADE");
        User user = users.save(User.newUser("payment-it@example.com", "test-hash", "Payment IT"));
        holder = user.getId();
        Show show = shows.save(Show.createWithId(UUID.randomUUID(), holder, "Payment IT", "Hall", null, null, ShowStatus.PUBLISHED));
        OffsetDateTime now = OffsetDateTime.now();
        ShowSchedule schedule = schedules.save(ShowSchedule.create(show.getId(), now.plusDays(7), now.plusDays(7).plusHours(2), now.minusDays(1)));
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
}
