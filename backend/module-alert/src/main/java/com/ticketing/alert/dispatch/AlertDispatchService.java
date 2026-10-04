package com.ticketing.alert.dispatch;

import com.ticketing.auth.domain.User;
import com.ticketing.auth.domain.UserRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 알람 발송 오케스트레이션.
 *
 * <h2>흐름</h2>
 * <pre>
 *   1) DispatchContext 의 channel → 매칭되는 AlertChannelSender 선택
 *   2) ChannelQuotaService.tryConsume(channel) → false 면 QUOTA_EXCEEDED 격리
 *   3) sender.send(ctx) → DispatchResult
 *   4) alert_dispatches 영속 + 메트릭 기록
 * </pre>
 */
@Service
public class AlertDispatchService {

    private static final Logger log = LoggerFactory.getLogger(AlertDispatchService.class);

    private final Map<String, AlertChannelSender> sendersByChannel;
    private final ChannelQuotaService quotaService;
    private final AlertDispatchRepository dispatchRepository;
    private final UserRepository userRepository;
    private final MeterRegistry meterRegistry;
    private final Counter dispatchedCounter;
    private final Counter quotaCounter;

    public AlertDispatchService(List<AlertChannelSender> senders,
                                 ChannelQuotaService quotaService,
                                 AlertDispatchRepository dispatchRepository,
                                 UserRepository userRepository,
                                 MeterRegistry meterRegistry) {
        this.sendersByChannel = senders.stream()
                .collect(Collectors.toUnmodifiableMap(AlertChannelSender::channel, s -> s));
        this.quotaService = quotaService;
        this.dispatchRepository = dispatchRepository;
        this.userRepository = userRepository;
        this.meterRegistry = meterRegistry;
        this.dispatchedCounter = Counter.builder("alert.dispatched")
                .description("Alert dispatches recorded (any status)")
                .register(meterRegistry);
        this.quotaCounter = Counter.builder("alert.quota.exceeded")
                .description("Alert dispatches blocked by channel quota")
                .register(meterRegistry);
    }

    /**
     * 알람 1건 발송 처리.
     *
     * @param channel 발송 채널 (FCM/SMTP/WEBHOOK)
     * @param ctxWithoutEmail email 제외 컨텍스트 — 본 메서드가 userRepository 로 채움
     */
    @Transactional
    public DispatchResult dispatch(String channel, DispatchContext ctxWithoutEmail) {
        AlertChannelSender sender = sendersByChannel.get(channel);
        if (sender == null) {
            DispatchResult r = DispatchResult.failed("지원하지 않는 채널: " + channel);
            record(ctxWithoutEmail.alertId(), channel, r);
            return r;
        }

        // ---- quota 체크 -----------------------------------------------------
        if (!quotaService.tryConsume(channel)) {
            quotaCounter.increment();
            DispatchResult r = DispatchResult.quotaExceeded();
            record(ctxWithoutEmail.alertId(), channel, r);
            return r;
        }

        // ---- user email 룩업 + ctx 보강 --------------------------------------
        DispatchContext ctx = ctxWithoutEmail;
        if (ctx.userEmail() == null) {
            User user = userRepository.findById(ctxWithoutEmail.userId()).orElse(null);
            ctx = new DispatchContext(
                    ctxWithoutEmail.alertId(),
                    ctxWithoutEmail.userId(),
                    user != null ? user.getEmail() : null,
                    ctxWithoutEmail.scheduleId(),
                    ctxWithoutEmail.sectionId(),
                    ctxWithoutEmail.thresholdPrice(),
                    ctxWithoutEmail.observedPrice());
        }

        // ---- 발송 -----------------------------------------------------------
        DispatchResult result;
        try {
            result = sender.send(ctx);
        } catch (Exception ex) {
            // sender 가 결과 변환을 잊었을 때 안전망.
            log.warn("alert sender 가 예외를 throw 했습니다 channel={}: {}", channel, ex.toString());
            result = DispatchResult.failed(ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
        record(ctx.alertId(), channel, result);
        return result;
    }

    /** alert_dispatches 영속 + 채널/상태별 카운터. */
    private void record(UUID alertId, String channel, DispatchResult result) {
        dispatchRepository.save(AlertDispatch.of(alertId, channel, result.status(), result.error()));
        dispatchedCounter.increment();
        // 상태별 분해 — 동적 태그 생성. counter() 캐싱은 MeterRegistry 가 처리.
        meterRegistry.counter("alert.dispatch.outcome",
                Tags.of("channel", channel, "status", result.status())).increment();
    }
}
