package com.ticketing.pricing;

import com.ticketing.common.time.AppClock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;

/**
 * module-pricing 단독 통합 테스트용 부트 애플리케이션.
 *
 * <h2>스캔 대상</h2>
 * <ul>
 *   <li>{@code com.ticketing.pricing} — 본 모듈</li>
 *   <li>{@code com.ticketing.show} — pricing 이 module-show 의 Seat/Section 을 참조</li>
 *   <li>{@code com.ticketing.seat} — module-seat 도 pricing 이 참조(SeatCounter 등)</li>
 *   <li>{@code com.ticketing.auth} — SecurityConfig 및 UserRepository (FK 이유는 다른 IT 와 동일)</li>
 *   <li>{@code com.ticketing.outbox} — outbox 인프라</li>
 *   <li>{@code com.ticketing.common} — 공통 유틸</li>
 * </ul>
 *
 * <p>
 *   Kafka(Streams) 는 이 IT 에서 굳이 띄우지 않는다. 캔들 조회는 Kafka 와 무관하고,
 *   Streams 관련 프로퍼티가 없으면 자동 설정이 건너뛰어짐. 필요한 개별 프로퍼티는 base 에서 정리.
 * </p>
 */
// @SpringBootApplication 은 excludeFilters 속성을 노출하지 않는다(컴파일 불가) —
// 같은 효과를 내도록 구성 요소(@SpringBootConfiguration + @EnableAutoConfiguration +
// @ComponentScan)로 분해해 excludeFilters 를 @ComponentScan 에 직접 건다.
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(
        basePackages = {
                "com.ticketing.pricing",
                "com.ticketing.show",
                "com.ticketing.seat",
                "com.ticketing.auth",
                "com.ticketing.outbox",
                "com.ticketing.common"
        },
        // com.ticketing.pricing.streams.* 는 @EnableKafkaStreams 를 켜 Kafka 브로커를 요구한다.
        // 이 IT 는 캔들 조회만 검증하므로 streams 서브패키지를 통째로 제외.
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "com\\.ticketing\\.pricing\\.streams\\..*")
)
@Import(AppClock.class)
public class PricingIntegrationTestApp {

    @Bean
    public MeterRegistry testMeterRegistry() {
        return new SimpleMeterRegistry();
    }

    /**
     * streams 패키지를 제외했지만 {@code PricingTickService} 는 DemandSignalReader 를 요구한다.
     * 이 IT 는 캔들 조회 검증이 목적이라 수요 신호가 필요 없으므로 항상 0 을 돌려주는
     * 스텁으로 대체 — Kafka Streams 없이 컨텍스트가 뜬다.
     */
    /**
     * 스캔 대상인 common-outbox 의 OutboxPublisher 가 KafkaTemplate 을 요구한다.
     * 프로듀서는 lazy 라 send 전까지 브로커에 연결하지 않고, IT 는 outbox 를
     * 비활성화하므로 접속이 일어나지 않는다 (module-alert IT 앱과 동일 패턴).
     */
    @Bean
    public org.springframework.kafka.core.KafkaTemplate<String, String> testKafkaTemplate() {
        java.util.Map<String, Object> cfg = java.util.Map.of(
                org.apache.kafka.clients.producer.ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092",
                org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                org.apache.kafka.common.serialization.StringSerializer.class,
                org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                org.apache.kafka.common.serialization.StringSerializer.class);
        return new org.springframework.kafka.core.KafkaTemplate<>(
                new org.springframework.kafka.core.DefaultKafkaProducerFactory<>(cfg));
    }

    @Bean
    public com.ticketing.pricing.streams.DemandSignalReader testDemandSignalReader() {
        return new com.ticketing.pricing.streams.DemandSignalReader(null) {
            @Override
            public long readRecentDemand(java.util.UUID scheduleId, java.util.UUID sectionId,
                                         java.time.Duration lookback) {
                return 0L;
            }
        };
    }
}
