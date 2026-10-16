package com.ticketing.alert.refund;

import com.ticketing.alert.dispatch.AlertChannelProperties;
import com.ticketing.alert.dispatch.ChannelQuotaService;
import com.ticketing.auth.domain.UserRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.util.Map;

/** SMTP and mock FCM are at-least-once: a process crash after provider acceptance may duplicate delivery. */
@Component
@ConditionalOnProperty(name="app.alert.refund-delivery.enabled", havingValue="true")
public class RefundNotificationWorker {
    private final RefundNotificationQueue queue;
    private final UserRepository users;
    private final JavaMailSender mail;
    private final AlertChannelProperties props;
    private final ChannelQuotaService quota;
    private final RestClient fcm;
    public RefundNotificationWorker(RefundNotificationQueue queue, UserRepository users, JavaMailSender mail,
                                    AlertChannelProperties props, ChannelQuotaService quota) {
        this.queue=queue; this.users=users; this.mail=mail; this.props=props; this.quota=quota;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(java.time.Duration.ofSeconds(3));
        factory.setReadTimeout(java.time.Duration.ofSeconds(5));
        this.fcm=RestClient.builder().requestFactory(factory).baseUrl(props.getFcm().getMockUrl()).build();
    }
    @Scheduled(fixedDelayString="${app.alert.refund-delivery.interval:PT5S}")
    public void deliver() {
        queue.backfill();
        for (int i=0; i<20; i++) {
            var claimed=queue.claim();
            if (claimed.isEmpty()) return;
            var work=claimed.getFirst();
            String error=null;
            try {
                if (!quota.tryConsume(work.channel())) throw new IllegalStateException("ChannelQuotaExceeded");
                var user=users.findById(work.userId()).orElseThrow();
                String body=String.format("결제 %s의 %,d원이 전액 환불되었습니다. 예매 내역에서 상태를 확인해 주세요. (Mock PG 데모)", work.paymentId(), work.amount());
                if (work.channel().equals("SMTP")) {
                    var message=new SimpleMailMessage();
                    message.setFrom(props.getSmtp().getFrom()); message.setTo(user.getEmail());
                    message.setSubject("[티켓팅] 환불 완료"); message.setText(body); mail.send(message);
                } else {
                    fcm.post().body(Map.of("message", Map.of("token", user.getEmail(),
                            "notification", Map.of("title", "환불 완료", "body", body),
                            "data", Map.of("paymentId", work.paymentId().toString(), "type", "PAYMENT_REFUNDED"))))
                            .retrieve().toBodilessEntity();
                }
            } catch (Exception ex) { error=ex.getClass().getSimpleName(); }
            queue.complete(work, error);
        }
    }
}
