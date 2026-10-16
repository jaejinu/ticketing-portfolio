package com.ticketing.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.bootstrap.DefaultBootstrapContext;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 프로필 없이 켜면 멈춘다 — 예전 기본값 local 은 공개 비밀번호의 시드 관리자를 만들었다 (QA P3). */
class ExplicitProfileTest {

    @Test
    void refusesToStartWithoutProfile() {
        StandardEnvironment env = new StandardEnvironment();
        assertThatThrownBy(() -> TicketingApplication.requireExplicitProfile().onApplicationEvent(event(env)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SPRING_PROFILES_ACTIVE");

        env.setActiveProfiles("local");
        assertThatCode(() -> TicketingApplication.requireExplicitProfile().onApplicationEvent(event(env)))
                .doesNotThrowAnyException();
    }

    private static ApplicationEnvironmentPreparedEvent event(StandardEnvironment env) {
        return new ApplicationEnvironmentPreparedEvent(new DefaultBootstrapContext(),
                new SpringApplication(TicketingApplication.class), new String[0], env);
    }
}
