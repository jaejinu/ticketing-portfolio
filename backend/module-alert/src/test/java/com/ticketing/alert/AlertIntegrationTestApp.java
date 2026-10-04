package com.ticketing.alert;

import com.ticketing.common.time.AppClock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Map;

/**
 * module-alert 단독 통합 테스트용 부트 애플리케이션.
 *
 * <h2>스캔 대상</h2>
 * <ul>
 *   <li>{@code com.ticketing.alert} — 본 모듈</li>
 *   <li>{@code com.ticketing.auth} — UserRepository (알람이 유저 email 을 lookup)</li>
 *   <li>{@code com.ticketing.outbox} — 공통 인프라</li>
 *   <li>{@code com.ticketing.common} — AppClock 등</li>
 * </ul>
 *
 * <h2>제외</h2>
 * <p>
 *   {@link MailSenderAutoConfiguration} 는 mail host 가 반드시 필요해 IT 부팅을 어렵게 한다.
 *   quota IT 는 sender 를 실제로 호출하지 않으므로 mail 자동 설정을 제외한다.
 *   Kafka(alert dispatch consumer) 도 IT 관심사가 아니면 프로퍼티로 억제.
 * </p>
 */
@SpringBootApplication(
        scanBasePackages = {
                "com.ticketing.alert",
                "com.ticketing.auth",
                "com.ticketing.outbox",
                "com.ticketing.common"
        },
        exclude = { MailSenderAutoConfiguration.class }
)
@Import(AppClock.class)
public class AlertIntegrationTestApp {

    @Bean
    public MeterRegistry testMeterRegistry() {
        return new SimpleMeterRegistry();
    }

    /**
     * MailSenderAutoConfiguration 을 제외했으므로 {@code SmtpAlertSender} 가 요구하는
     * JavaMailSender 빈을 직접 채워야 컨텍스트가 뜬다 (없으면 UnsatisfiedDependency 로
     * quota IT 전체가 부팅 실패). IT 는 실제 발송을 하지 않으므로 연결 설정 없는
     * 기본 구현이면 충분하다.
     */
    @Bean
    public JavaMailSender testJavaMailSender() {
        return new JavaMailSenderImpl();
    }

    /**
     * KafkaAutoConfiguration 을 제외했지만 스캔 대상인 common-outbox 의
     * {@code OutboxPublisher} 가 KafkaTemplate 을 요구한다. 프로듀서는 lazy 라
     * 실제 send 전까지 브로커에 연결하지 않고, IT 는 outbox 를 비활성화하므로
     * 접속이 일어나지 않는다 — 컨텍스트 부팅용 최소 빈.
     */
    @Bean
    public KafkaTemplate<String, String> testKafkaTemplate() {
        Map<String, Object> cfg = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092",
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(cfg));
    }
}
