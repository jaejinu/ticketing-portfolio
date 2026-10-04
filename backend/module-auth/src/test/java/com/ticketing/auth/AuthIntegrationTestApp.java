package com.ticketing.auth;

import com.ticketing.common.time.AppClock;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * module-auth 단독 통합 테스트용 부트 애플리케이션.
 *
 * <p>
 *   app-gateway 전체를 띄우지 않고도 인증 흐름만 검증 가능하게 한다.
 *   {@link AppClock} 는 common-domain 에 있어 컴포넌트 스캔에 포함되지 않으므로 명시 import.
 * </p>
 */
@SpringBootApplication(scanBasePackages = "com.ticketing.auth")
@Import(AppClock.class)
public class AuthIntegrationTestApp {
}
