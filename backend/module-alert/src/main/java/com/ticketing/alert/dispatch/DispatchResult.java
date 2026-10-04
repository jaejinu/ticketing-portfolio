package com.ticketing.alert.dispatch;

/**
 * 채널 sender 의 결과.
 *
 * @param status DispatchStatus 상수
 * @param error  실패 사유 (null 가능, SENT 면 null)
 */
public record DispatchResult(String status, String error) {

    public static DispatchResult sent() {
        return new DispatchResult(DispatchStatus.SENT, null);
    }

    public static DispatchResult failed(String error) {
        return new DispatchResult(DispatchStatus.FAILED, error);
    }

    public static DispatchResult skipped(String reason) {
        return new DispatchResult(DispatchStatus.SKIPPED, reason);
    }

    public static DispatchResult quotaExceeded() {
        return new DispatchResult(DispatchStatus.QUOTA_EXCEEDED, "channel quota exhausted");
    }
}
