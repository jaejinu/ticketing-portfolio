package com.ticketing.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * 멀티모듈 모놀리스의 단일 부트런 진입점.
 *
 * <h2>scanBasePackages 가 com.ticketing 인 이유</h2>
 * <p>
 *   각 module-* 의 패키지가 {@code com.ticketing.auth}, {@code com.ticketing.show}, ... 처럼 분포되어 있어
 *   상위 패키지 {@code com.ticketing} 를 컴포넌트 스캔 루트로 지정해야 모든 모듈의 빈이 등록된다.
 *   기본값(이 클래스의 패키지 = com.ticketing.gateway) 만으로는 다른 모듈이 스캔되지 않는다.
 * </p>
 */
@SpringBootApplication
@ComponentScan(basePackages = "com.ticketing")
public class TicketingApplication {

    public static void main(String[] args) {
        SpringApplication.run(TicketingApplication.class, args);
    }
}
