package com.ticketing.alert.dispatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.alert.dispatch.sender.SmtpAlertSender;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Real application sender against an isolated SMTP sink; no external delivery. */
class SmtpMailpitIntegrationTest {
    @Test
    void priceAlertArrivesWithRecipientAndKoreanContent() throws Exception {
        try (var mailpit = new GenericContainer<>(
                "axllent/mailpit:v1.31.4@sha256:b68349e3a014b90c5610bfb26b2ae36f3892d7b8cf25ee140c6c71c98d2fcf48")
                .withExposedPorts(1025, 8025)
                .waitingFor(Wait.forHttp("/readyz").forPort(8025))) {
            mailpit.start();
            var mail = new JavaMailSenderImpl();
            mail.setHost(mailpit.getHost());
            mail.setPort(mailpit.getMappedPort(1025));
            mail.setDefaultEncoding("UTF-8");
            var props = new AlertChannelProperties();
            var alertId = UUID.randomUUID();
            var result = new SmtpAlertSender(mail, props).send(new DispatchContext(
                    alertId, UUID.randomUUID(), "smtp-check@example.invalid",
                    UUID.randomUUID(), UUID.randomUUID(), 100_000L, 90_000L));
            assertThat(result.status()).isEqualTo(DispatchStatus.SENT);

            var client = HttpClient.newHttpClient();
            var mapper = new ObjectMapper();
            String base = "http://" + mailpit.getHost() + ":" + mailpit.getMappedPort(8025);
            var list = client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/messages"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(list.statusCode()).isEqualTo(200);
            var inbox = mapper.readTree(list.body());
            assertThat(inbox.path("total").asInt()).isEqualTo(1);
            String id = inbox.path("messages").get(0).path("ID").asText();
            var response = client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/message/" + id))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            var message = mapper.readTree(response.body());
            assertThat(message.path("Subject").asText()).isEqualTo("[티켓팅] 가격 하락 알림");
            assertThat(message.path("To").get(0).path("Address").asText())
                    .isEqualTo("smtp-check@example.invalid");
            assertThat(message.path("Text").asText())
                    .contains("90,000", "100,000", alertId.toString());
        }
    }
}
