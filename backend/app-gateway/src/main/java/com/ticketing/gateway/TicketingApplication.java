package com.ticketing.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
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
        SpringApplication app = new SpringApplication(TicketingApplication.class);
        app.addListeners(requireExplicitProfile());
        app.run(args);
    }

    /**
     * 프로필 없이 켜지 않는다.
     *
     * <p>
     *   예전엔 기본 프로필이 local 이라, 프로필을 깜빡한 배포가 공개된 데모 비밀번호의 시드 관리자 계정을
     *   만들고 로컬용 기본값으로 떴다 (2026-10-13 QA P3). 이제 SPRING_PROFILES_ACTIVE 를 꼭 지정해야 한다.
     *   개발용 {@code ./gradlew :app-gateway:bootRun} 은 build.gradle.kts 가 local 을 넣어 준다.
     * </p>
     */
    static ApplicationListener<ApplicationEnvironmentPreparedEvent> requireExplicitProfile() {
        return event -> {
            if (event.getEnvironment().getActiveProfiles().length == 0) {
                throw new IllegalStateException(
                        "실행 프로필이 없습니다. SPRING_PROFILES_ACTIVE 를 지정하세요 (예: local, local,public-demo).");
            }
        };
    }
}
