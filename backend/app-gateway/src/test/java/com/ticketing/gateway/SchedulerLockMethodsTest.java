package com.ticketing.gateway;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ShedLock 은 값을 돌려주는 메서드를 잠그지 못하고 실행마다 LockingNotSupportedException 을 낸다.
 * 모듈 테스트에는 ShedLock AOP 가 없어 2026-10-14 배포 뒤에야 지난 회차 자동 종료가 계속 실패한 걸 알았다.
 */
class SchedulerLockMethodsTest {

    @Test
    void everySchedulerLockMethodReturnsVoid() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        List<String> locked = new ArrayList<>();
        List<String> wrong = new ArrayList<>();
        List<Class<?>> types = new ArrayList<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("com.ticketing")) {
            types.add(ClassUtils.forName(bd.getBeanClassName(), getClass().getClassLoader()));
        }
        // 스캐너는 @ConditionalOnProperty 로 꺼진 빈을 건너뛴다 — 조건부 작업은 직접 넣는다.
        types.add(com.ticketing.show.application.ScheduleAutoCloseJob.class);
        for (Class<?> type : types) {
            for (Method m : type.getDeclaredMethods()) {
                if (m.isAnnotationPresent(SchedulerLock.class)) {
                    locked.add(type.getSimpleName() + "." + m.getName());
                    if (m.getReturnType() != void.class) wrong.add(type.getSimpleName() + "." + m.getName());
                }
            }
        }
        assertThat(locked).as("검사 대상이 실제로 잡혀야 한다").contains("ScheduleAutoCloseJob.scheduledClose");
        assertThat(wrong).isEmpty();
    }
}
