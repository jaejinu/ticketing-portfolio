package com.ticketing.paymentsaga.application;

/**
 * Mock PG 호출 자체가 실패한 경우 (네트워크/타임아웃/4xx/5xx).
 *
 * <p>
 *   {@link PaymentSagaService} 가 잡아서 Payment.fail("PG_UNAVAILABLE", ...) 로 변환.
 * </p>
 */
public class PgUnavailableException extends RuntimeException {

    public PgUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
