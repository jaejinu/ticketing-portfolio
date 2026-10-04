package com.ticketing.auth.domain;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * BCrypt 기반 비밀번호 해시 추상화.
 *
 * <h2>왜 BCrypt 인가</h2>
 * <ul>
 *   <li>scrypt/argon2 가 더 강력하지만 JVM 라이브러리 의존을 추가해야 한다.
 *       Spring Security 가 기본 제공하는 BCrypt 가 운영 자료가 가장 풍부하다.</li>
 *   <li>"strength=12" 는 OWASP 2023 기준 일반 서버 CPU 에서 ~300ms — 사용자 체감
 *       가능 범위 내이지만 brute-force 비용을 충분히 키우는 균형점.</li>
 *   <li>strength=8 은 너무 약하고(수 ms), 14 는 1초+ 라 회원가입 UX 가 망가진다.</li>
 * </ul>
 *
 * <h2>설정 가능성</h2>
 * <ul>
 *   <li>{@code app.auth.bcrypt.strength} (기본 12) 로 환경별 조정 가능.</li>
 *   <li>테스트 프로파일에선 strength=4 로 낮춰 통합 테스트 속도를 확보한다.</li>
 * </ul>
 */
@Component
public class PasswordHasher {

    private final BCryptPasswordEncoder encoder;

    public PasswordHasher(@Value("${app.auth.bcrypt.strength:12}") int strength) {
        // strength 범위: 4~31. Spring Security 가 범위 검증해 줌.
        this.encoder = new BCryptPasswordEncoder(strength);
    }

    /** 평문 → BCrypt 해시. salt 는 BCrypt 내부에서 자동 생성. */
    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    /** 평문이 해시와 일치하는지 검증. timing-safe. */
    public boolean matches(String rawPassword, String hashed) {
        return encoder.matches(rawPassword, hashed);
    }
}
