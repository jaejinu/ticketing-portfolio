package com.ticketing.gateway.health;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 간이 헬스 체크 엔드포인트.
 *
 * <p>
 *   Actuator 의 {@code /actuator/health} 는 8081 포트(management.server.port) 에 위치한다.
 *   하지만 외부 LB/프론트 헬스체크는 8080 에서 동작하길 기대하는 경우가 많아
 *   8080 에 단순 200 응답을 주는 {@code /health} 도 함께 노출한다.
 * </p>
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
