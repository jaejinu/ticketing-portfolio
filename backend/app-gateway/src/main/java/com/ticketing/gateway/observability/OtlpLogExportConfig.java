package com.ticketing.gateway.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.resources.Resource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * 백엔드 로그를 OTLP 로 collector(→ Loki) 에 보낸다.
 *
 * <p>
 *   예전엔 로그가 Loki 에 들어가지 않았다 (2026-10-13 QA P3) — local 프로필이 OTel 스타터 자동 설정을
 *   통째로 끄고 있어서다. 스타터를 다시 켜면 Kafka 메시지마다(가격 틱 초당 수 건) 트레이스가 생겨 Tempo 가
 *   넘친다. 그래서 스타터는 끈 채로, 로그 전용 SDK 하나만 만들어 logback 루트에 붙인다.
 *   트레이스·메트릭은 지금처럼 Micrometer 가 보낸다.
 * </p>
 *
 * <p>
 *   {@code deployment.environment} 는 {@code app.telemetry.environment}(데모는 public-demo) —
 *   collector 는 값이 없을 때만 local 을 넣는다.
 * </p>
 */
@Configuration
@ConditionalOnProperty(name = "app.telemetry.logs.enabled", havingValue = "true")
public class OtlpLogExportConfig {

    private static final String APPENDER_NAME = "OTLP";

    private final String endpoint;
    private final String serviceName;
    private final String environment;
    private SdkLoggerProvider loggerProvider;

    public OtlpLogExportConfig(@Value("${otel.exporter.otlp.endpoint:http://localhost:4318}") String endpoint,
                               @Value("${otel.resource.attributes.service.name:ticketing-backend}") String serviceName,
                               @Value("${app.telemetry.environment:local}") String environment) {
        this.endpoint = endpoint;
        this.serviceName = serviceName;
        this.environment = environment;
    }

    @PostConstruct
    void install() {
        Resource resource = Resource.getDefault().merge(Resource.create(Attributes.builder()
                .put("service.name", serviceName)
                .put("deployment.environment", environment)
                .build()));
        loggerProvider = SdkLoggerProvider.builder()
                .setResource(resource)
                .addLogRecordProcessor(BatchLogRecordProcessor.builder(
                        OtlpHttpLogRecordExporter.builder().setEndpoint(endpoint + "/v1/logs").build()).build())
                .build();
        OpenTelemetry otel = OpenTelemetrySdk.builder().setLoggerProvider(loggerProvider).build();

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        if (root.getAppender(APPENDER_NAME) == null) {
            OpenTelemetryAppender appender = new OpenTelemetryAppender();
            appender.setName(APPENDER_NAME);
            appender.setContext(context);
            appender.setCaptureExperimentalAttributes(false);
            appender.start();
            root.addAppender(appender);
        }
        OpenTelemetryAppender.install(otel);
    }

    @PreDestroy
    void shutdown() {
        if (loggerProvider != null) loggerProvider.close();
    }
}
