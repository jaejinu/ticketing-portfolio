package com.ticketing.paymentsaga.application;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

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
 *     504 { code:"GATEWAY_TIMEOUT" }            → 게이트웨이 지연 (PG 장애로 취급)
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
 *   saga 가 잡아서 PaymentStatus.FAILED 로 마킹.
 * </p>
 */
@Component
public class MockPgClient {

    private final RestClient restClient;
    private final PaymentProperties props;

    public MockPgClient(PaymentProperties props) {
        this.props = props;
        // 별도 RestClient.Builder 빈을 만들지 않고 직접 생성 — 단순화.
        // production 에선 connect timeout / read timeout 분리 + connection pool 설정 권장.
        this.restClient = RestClient.builder()
                .baseUrl(props.getMockPgBaseUrl())
                .build();
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
            // 200 = 승인. approveNo 를 pgTxnId 로 보존.
            return new MockPgResponse(true,
                    String.valueOf(res.get("approveNo")),
                    String.valueOf(res.get("code")),
                    null);
        } catch (RestClientResponseException ex) {
            // 402 = 카드 거절 — PG 는 정상 동작했고 "결제만" 거절. 보상 대상 아님 → approved=false.
            if (ex.getStatusCode().value() == 402) {
                return new MockPgResponse(false, null, "LIMIT_EXCEEDED", "카드 한도를 초과했습니다.");
            }
            // 그 외 4xx/5xx(504 포함) = PG 장애 취급 → saga 가 FAILED 마킹 + 보상.
            throw new PgUnavailableException("PG 호출 실패: " + ex.getMessage(), ex);
        } catch (PgUnavailableException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new PgUnavailableException("PG 호출 실패: " + ex.getMessage(), ex);
        }
    }
}
