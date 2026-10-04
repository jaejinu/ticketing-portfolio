package com.ticketing.show;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * module-show 모듈 자동 설정 진입점.
 *
 * <p>
 *   app-gateway 가 {@code com.ticketing} 패키지를 component scan 하므로 본 @Configuration 이
 *   인식되면 EntityScan / EnableJpaRepositories 가 의도한 패키지에 적용된다.
 * </p>
 *
 * <p>
 *   왜 명시 EntityScan 인가:
 *   기본 Spring Boot 의 @EntityScan 자동 적용 범위가 메인 클래스 패키지 하위라
 *   {@code com.ticketing.gateway} 아래에는 show 엔티티가 없다.
 *   본 모듈의 도메인 패키지를 명시 지정한다.
 * </p>
 */
@Configuration
@EntityScan(basePackages = "com.ticketing.show.domain")
@EnableJpaRepositories(basePackages = "com.ticketing.show.domain")
public class ShowModuleConfig {
}
