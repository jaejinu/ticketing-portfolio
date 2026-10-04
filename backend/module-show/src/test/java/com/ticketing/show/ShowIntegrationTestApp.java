package com.ticketing.show;

import com.ticketing.common.time.AppClock;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * module-show 단독 통합 테스트용 부트 애플리케이션.
 *
 * <p>
 *   app-gateway 전체를 띄우지 않고도 공연 조회/등록 흐름을 검증한다.
 *   다만 module-show 가 module-auth 의 {@code User/UserRepository} 와 SecurityConfig 빈을
 *   직접 참조(SeedRunner, RoleAdminController) 하므로 {@code com.ticketing.auth} 도 같이 스캔한다.
 * </p>
 *
 * <p>
 *   {@link AppClock} 는 common-domain 에 있어 component scan 범위 밖이라 명시 import.
 *   (SectionCommandService 가 Clock 빈을 주입받기 때문에 필요)
 * </p>
 *
 * <p>
 *   <b>왜 두 모듈을 같이 띄우는가</b>:<br>
 *   1) SecurityConfig 가 com.ticketing.auth 에 있음 → 빈을 등록하지 않으면 모든 요청에 대해
 *      Spring Security 자동 구성이 default chain 을 만들고 403 을 뱉을 수 있어 의도가 흐려진다.
 *   2) SeedRunner / RoleAdminController 가 컴포넌트로 등록되려면 UserRepository 빈이 있어야 한다.
 *   3) shows.organizer_id 가 users.id 를 FK 로 참조 → users 테이블 마이그레이션이 필요한데,
 *      module-auth 의 V001__auth_users.sql 가 classpath 에 있어 자동 적용된다.
 * </p>
 */
@SpringBootApplication(scanBasePackages = {"com.ticketing.show", "com.ticketing.auth"})
@Import(AppClock.class)
public class ShowIntegrationTestApp {
}
