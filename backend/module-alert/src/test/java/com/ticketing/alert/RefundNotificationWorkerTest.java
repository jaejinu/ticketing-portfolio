package com.ticketing.alert;

import com.ticketing.alert.refund.*;
import com.ticketing.alert.dispatch.*;
import com.ticketing.auth.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RefundNotificationWorkerTest {
    @Test
    void smtpAndMockPushUseRefundPayloadAndRecordSuccess() throws Exception {
        var queue=mock(RefundNotificationQueue.class);
        var users=mock(UserRepository.class);
        var mail=mock(JavaMailSender.class);
        var quota=mock(ChannelQuotaService.class);
        when(quota.tryConsume(anyString())).thenReturn(true);
        var user=User.newUser("buyer@example.test","hash","구매자");
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        var payment=UUID.randomUUID();
        var smtp=new RefundNotificationQueue.Work(payment,"SMTP",user.getId(),70000,1,UUID.randomUUID());
        var push=new RefundNotificationQueue.Work(payment,"FCM",user.getId(),70000,1,UUID.randomUUID());
        when(queue.claim()).thenReturn(List.of(smtp),List.of(push),List.of());
        var received=new AtomicReference<String>();
        var server=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/push", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200,-1); exchange.close();
        });
        server.start();
        try {
            var props=new AlertChannelProperties();
            props.getFcm().setMockUrl("http://127.0.0.1:"+server.getAddress().getPort()+"/push");
            new RefundNotificationWorker(queue,users,mail,props,quota).deliver();
            verify(mail).send(argThat((SimpleMailMessage m) -> m.getSubject().contains("환불 완료") && m.getText().contains("70,000") && m.getTo()[0].equals("buyer@example.test")));
            assertThat(received.get()).contains("PAYMENT_REFUNDED",payment.toString(),"70,000");
            verify(queue).complete(smtp,null); verify(queue).complete(push,null);
        } finally { server.stop(0); }
    }
}
