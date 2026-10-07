package com.ticketing.paymentsaga.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(10)
class MockPgClientTest {
    private HttpServer server;
    private ExecutorService executor;
    private PaymentProperties props;
    private final CountDownLatch releaseResponse = new CountDownLatch(1);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.start();
        props = new PaymentProperties();
        props.setMockPgBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        props.setMockPgTimeout(Duration.ofSeconds(2));
    }

    @AfterEach
    void stopServer() throws InterruptedException {
        releaseResponse.countDown();
        server.stop(0);
        executor.shutdownNow();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void approvalPreservesRequestAndTransactionId() throws Exception {
        AtomicReference<String> request = new AtomicReference<>();
        AtomicReference<String> method = new AtomicReference<>();
        server.createContext("/pay", exchange -> {
            method.set(exchange.getRequestMethod());
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"code\":\"APPROVED\",\"approveNo\":\"AP-123\"}");
        });
        UUID paymentId = UUID.randomUUID();
        MockPgResponse response = new MockPgClient(props).charge(paymentId, 120000);
        assertThat(response.approved()).isTrue();
        assertThat(response.pgTxnId()).isEqualTo("AP-123");
        assertThat(method.get()).isEqualTo("POST");
        var body = new ObjectMapper().readTree(request.get());
        assertThat(body.path("orderId").asText()).isEqualTo(paymentId.toString());
        assertThat(body.path("amount").asLong()).isEqualTo(120000);
        assertThat(body.path("cardToken").asText()).isEqualTo("mock-card");
    }

    @Test
    void declineRemainsABusinessResult() {
        server.createContext("/pay", exchange -> respond(exchange, 402, "{\"code\":\"LIMIT_EXCEEDED\"}"));
        MockPgResponse response = new MockPgClient(props).charge(UUID.randomUUID(), 120000);
        assertThat(response.approved()).isFalse();
        assertThat(response.code()).isEqualTo("LIMIT_EXCEEDED");
        assertThat(response.pgTxnId()).isNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 500, 504})
    void httpErrorsAreUnavailable(int status) {
        server.createContext("/pay", exchange -> respond(exchange, status, "{}"));
        assertThatThrownBy(() -> new MockPgClient(props).charge(UUID.randomUUID(), 120000))
                .isInstanceOf(PgUnavailableException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{}", "{\"code\":\"APPROVED\"}",
            "{\"code\":\"APPROVED\",\"approveNo\":\" \"}",
            "{\"code\":\"APPROVED\",\"approveNo\":123}",
            "{\"code\":\"DECLINED\",\"approveNo\":\"AP-123\"}", "not-json"})
    void malformedSuccessCannotApprovePayment(String body) {
        server.createContext("/pay", exchange -> respond(exchange, 200, body));
        assertThatThrownBy(() -> new MockPgClient(props).charge(UUID.randomUUID(), 120000))
                .isInstanceOf(PgUnavailableException.class);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stalledHeadersOrBodyRespectConfiguredTimeout(boolean sendHeaders) {
        props.setMockPgTimeout(Duration.ofMillis(200));
        CountDownLatch received = new CountDownLatch(1);
        server.createContext("/pay", exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (sendHeaders) {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 100);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
            }
            received.countDown();
            try {
                releaseResponse.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        long started = System.nanoTime();
        assertThatThrownBy(() -> new MockPgClient(props).charge(UUID.randomUUID(), 120000))
                .isInstanceOf(PgUnavailableException.class)
                .hasRootCauseInstanceOf(SocketTimeoutException.class);
        assertThat(received.getCount()).isZero();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT0S", "PT-1S", "PT0.000001S", "P30D"})
    void invalidTimeoutFailsBeforeAnyPayment(String timeout) {
        props.setMockPgTimeout(Duration.parse(timeout));
        assertThatThrownBy(() -> new MockPgClient(props))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.payment.mock-pg-timeout");
    }

    @Test
    void missingTimeoutFailsBeforeAnyPayment() {
        props.setMockPgTimeout(null);
        assertThatThrownBy(() -> new MockPgClient(props)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lookupAndCancellationValidateOrderIdentity() {
        UUID id = UUID.randomUUID();
        server.createContext("/payments/" + id, exchange -> {
            boolean cancel = exchange.getRequestURI().getPath().endsWith("/cancel");
            respond(exchange, 200, "{\"orderId\":\"" + id + "\",\"status\":\""
                    + (cancel ? "CANCELLED" : "APPROVED") + "\",\"approveNo\":\"AP-1\"}");
        });
        MockPgClient client = new MockPgClient(props);
        assertThat(client.lookup(id).status()).isEqualTo("APPROVED");
        assertThat(client.cancel(id).status()).isEqualTo("CANCELLED");
    }

    @Test
    void onlyNotFoundIsTreatedAsMissingOrder() {
        server.createContext("/payments/", exchange -> respond(exchange, 404, "{}"));
        assertThat(new MockPgClient(props).lookup(UUID.randomUUID()).status()).isEqualTo("NOT_FOUND");
        assertThatThrownBy(() -> new MockPgClient(props).cancel(UUID.randomUUID()))
                .isInstanceOf(PgUnavailableException.class);
    }

    @Test
    void wrongOrderOrUnconfirmedCancellationCannotSettle() {
        server.createContext("/payments/", exchange -> respond(exchange, 200,
                "{\"orderId\":\"wrong-order\",\"status\":\"CANCELLED\"}"));
        MockPgClient client = new MockPgClient(props);
        assertThatThrownBy(() -> client.lookup(UUID.randomUUID())).isInstanceOf(PgUnavailableException.class);
        assertThatThrownBy(() -> client.cancel(UUID.randomUUID())).isInstanceOf(PgUnavailableException.class);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        try (exchange) {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) exchange.getResponseBody().write(bytes);
        }
    }
}
