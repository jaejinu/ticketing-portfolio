package com.ticketing.auth.security;

import org.springframework.security.core.annotation.AuthenticationPrincipal;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 컨트롤러 메서드 파라미터에 붙여 {@link AuthPrincipal} 을 주입받는 헬퍼 어노테이션.
 *
 * <pre>
 *   {@code @GetMapping("/me")}
 *   public MeResponse me({@code @AuthenticatedUser} AuthPrincipal me) { ... }
 * </pre>
 *
 * <p>
 *   {@link AuthenticationPrincipal} 의 메타-어노테이션. errorOnInvalidType=true 로
 *   잘못된 타입 주입을 빌드 단계에 잡을 수 있도록 한다.
 * </p>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@AuthenticationPrincipal(errorOnInvalidType = true)
public @interface AuthenticatedUser {
}
