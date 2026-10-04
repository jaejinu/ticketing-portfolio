package com.ticketing.auth;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * module-auth 모듈 자동 설정 진입점.
 *
 * <p>
 *   app-gateway 가 {@code com.ticketing} 을 component scan 하므로 @Configuration 만 인식되면
 *   하위 {@link EntityScan} / {@link EnableJpaRepositories} 가 의도한 패키지에 정확히 적용된다.
 * </p>
 *
 * <p>
 *   <b>왜 이 클래스가 필요한가:</b>
 *   기본 Spring Boot 의 {@code @SpringBootApplication} 은 자기 패키지 하위만 JPA repository 로 인식한다.
 *   {@code com.ticketing.gateway} 아래에는 엔티티가 없으므로 명시적 EntityScan 지정이 필요.
 * </p>
 */
@Configuration
@EntityScan(basePackages = "com.ticketing.auth.domain")
@EnableJpaRepositories(basePackages = "com.ticketing.auth.domain")
public class AuthModuleConfig {
}
