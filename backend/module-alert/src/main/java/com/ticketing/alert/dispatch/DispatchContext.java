package com.ticketing.alert.dispatch;

import java.util.UUID;

/**
 * 채널 sender 에 전달되는 발송 컨텍스트.
 *
 * <p>
 *   ALERT_SENT payload 에서 추출한 정보 + 사용자 메타 (email) 를 한 record 로 묶는다.
 *   sender 구현은 channel() 만 보고 해당 채널 형식으로 가공.
 * </p>
 */
public record DispatchContext(
        UUID alertId,
        UUID userId,
        String userEmail,
        UUID scheduleId,
        UUID sectionId,
        long thresholdPrice,
        long observedPrice
) {
}
