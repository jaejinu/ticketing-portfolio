package com.ticketing.paymentsaga.application;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Mock PG 클라이언트.
 *
 * <h2>책임</h2>
 * <p>
 *   {@code POST {base-url}/pay} 로 결제 요청 → 응답을 {@link MockPgResponse} 로 매핑.
 *   Mock PG(infra/mock-services/mock-pg/server.js) 의 실제 계약:
 * </p>
 * <pre>
 *   POST /pay { orderId, amount, cardToken }
 *     200 { code:"APPROVED", approveNo, ... }   → 승인
 *     402 { code:"LIMIT_EXCEEDED", message }    → 거절 (보상 없이 FAILED 안내)
 *     지연 승인 → 클라이언트 timeout 뒤 GET /payments/{orderId}로 결과 확인
 * </pre>
 *
 * <h2>왜 RestClient 인가</h2>
 * <p>
 *   Spring 6.1 RestClient 는 동기 흐름이라 saga 코드의 try/catch 가 깔끔.
 *   WebClient 는 reactive 흐름이 필요할 때만. 결제는 동기 saga.
 * </p>
 *
 * <h2>예외</h2>
 * <p>
 *   HTTP 실패(4xx/5xx/timeout/IO) 는 {@link PgUnavailableException} 으로 변환.
 *   saga가 PENDING을 유지하고 조회 스케줄러로 승인 여부를 확인한다.
 * </p>
 */
@Component
public class MockPgClient {

    private final RestClient restClient;

    public MockPgClient(PaymentProperties props) {
        Duration timeout = props.getMockPgTimeout();
        if (timeout == null || timeout.compareTo(Duration.ofMillis(1)) < 0
                || timeout.compareTo(Duration.ofMillis(Integer.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException("app.payment.mock-pg-timeout must be between 1ms and 2147483647ms");
        }
        // 연결과 응답 읽기에 각각 적용한다. 전체 결제 처리의 총 제한 시간은 아니다.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        this.restClient = RestClient.builder()
                .baseUrl(props.getMockPgBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    public PgOrder lookup(UUID paymentId) {
        try {
            Map<?, ?> response = restClient.get().uri("/payments/{id}", paymentId)
                    .retrieve().body(Map.class);
            return parseOrder(response, paymentId, null);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) return new PgOrder("NOT_FOUND", null, null, null);
            throw new PgUnavailableException("PG 조회 실패", ex);
        } catch (Exception ex) {
            throw new PgUnavailableException("PG 조회 실패", ex);
        }
    }

    public PgOrder cancel(UUID paymentId) {
        try {
            Map<?, ?> response = restClient.post().uri("/payments/{id}/cancel", paymentId)
                    .retrieve().body(Map.class);
            return parseOrder(response, paymentId, "CANCELLED");
        } catch (Exception ex) {
            throw new PgUnavailableException("PG 취소 실패", ex);
        }
    }

    private PgOrder parseOrder(Map<?, ?> response, UUID paymentId, String requiredState) {
        if (response == null || !paymentId.toString().equals(response.get("orderId"))) {
            throw new PgUnavailableException("PG 주문 응답이 올바르지 않습니다.", null);
        }
        String state = response.get("status") instanceof String value ? value : "";
        String approval = response.get("approveNo") instanceof String value ? value : null;
        if (!java.util.Set.of("PENDING", "APPROVED", "DECLINED", "CANCELLED").contains(state)
                || (requiredState != null && !requiredState.equals(state))
                || ("APPROVED".equals(state) && (approval == null || approval.isBlank()))) {
            throw new PgUnavailableException("PG 주문 상태가 올바르지 않습니다.", null);
        }
        return new PgOrder(state, approval,
                response.get("code") instanceof String value ? value : null,
                response.get("message") instanceof String value ? value : null);
    }

    /**
     * 결제 승인 호출.
     *
     * @param paymentId 결제 식별자
     * @param amount    원 단위 금액
     * @return PG 응답
     * @throws PgUnavailableException 네트워크 오류 / 4xx / 5xx / timeout
     */
    public MockPgResponse charge(UUID paymentId, long amount) {
        // mock-pg 계약의 orderId 로 우리 paymentId 를 그대로 사용 — 로그 상호 추적 용이.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", paymentId.toString());
        body.put("amount", amount);
        body.put("cardToken", "mock-card");
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> res = restClient.post()
                    .uri("/pay")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            if (res == null) {
                throw new PgUnavailableException("PG 응답이 비어 있습니다.", null);
            }
            // HTTP 성공만으로 승인하지 않는다. 불완전한 응답을 거래 번호 "null"로 저장하지 않는다.
            if (!"APPROVED".equals(res.get("code"))
                    || !(res.get("approveNo") instanceof String approveNo) || approveNo.isBlank()) {
                throw new PgUnavailableException("PG 승인 응답이 올바르지 않습니다.", null);
            }
            return new MockPgResponse(true,
                    approveNo,
                    "APPROVED",
                    null);
        } catch (RestClientResponseException ex) {
            // 402 = 카드 거절 — PG 는 정상 동작했고 "결제만" 거절. 보상 대상 아님 → approved=false.
            if (ex.getStatusCode().value() == 402) {
                return new MockPgResponse(false, null, "LIMIT_EXCEEDED", "카드 한도를 초과했습니다.");
            }
            // 그 외 4xx/5xx(504 포함) = PG 장애 취급 → 결과 불명으로 PENDING 유지. 좌석은 즉시 해제하지 않는다.
            throw new PgUnavailableException("PG 호출 실패: " + ex.getMessage(), ex);
        } catch (PgUnavailableException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new PgUnavailableException("PG 호출 실패: " + ex.getMessage(), ex);
        }
    }
}
