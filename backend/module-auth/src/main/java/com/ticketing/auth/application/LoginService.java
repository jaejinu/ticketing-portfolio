package com.ticketing.auth.application;

import com.ticketing.auth.domain.PasswordHasher;
import com.ticketing.auth.domain.User;
import com.ticketing.auth.domain.UserRepository;
import com.ticketing.auth.domain.UserStatus;
import com.ticketing.common.error.BusinessException;
import com.ticketing.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Optional;

/**
 * 로그인 유스케이스.
 *
 * <h2>흐름</h2>
 * <pre>
 *   1) email 정규화 → user 조회
 *      - 없음 : INVALID_CREDENTIALS (사용자 존재 여부 노출 방지)
 *   2) status == DELETED   → INVALID_CREDENTIALS
 *   3) lockService.checkLock(userId)
 *      - 잠금 중 → ACCOUNT_LOCKED(unlockAt)
 *   4) passwordHasher.matches(raw, hash)
 *      - 불일치 → lockService.recordFailure(user)
 *                  - 잠금 트리거되면 ACCOUNT_LOCKED 반환, 아니면 INVALID_CREDENTIALS
 *   5) lockService.recordSuccess(user) → 카운터/잠금 해제
 *   6) TokenService.issuePair(user) 호출 (외부에서 결합)
 * </pre>
 *
 * <h2>타이밍 공격 방어 (간소화)</h2>
 * - 사용자 존재 여부에 따른 응답 시간 차이를 줄이려면 "찾을 수 없을 때도 dummy BCrypt 호출" 같은
 *   방어가 가능하나, 본 단계에서는 단순화를 위해 생략하고 동일한 에러 코드(INVALID_CREDENTIALS)
 *   만 보장한다. 운영 단계에선 dummy hash compare 추가 권장.
 */
@Service
public class LoginService {

    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final AccountLockService lockService;
    private final Clock clock;

    public LoginService(UserRepository userRepository,
                        PasswordHasher passwordHasher,
                        AccountLockService lockService,
                        Clock clock) {
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
        this.lockService = lockService;
        this.clock = clock;
    }

    @Transactional
    public User authenticate(String email, String rawPassword) {
        String normalized = email == null ? "" : email.toLowerCase(Locale.ROOT).trim();
        Optional<User> maybe = userRepository.findByEmail(normalized);
        if (maybe.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        User user = maybe.get();
        if (user.getStatus() == UserStatus.DELETED) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        // 잠금 우선 검사. 비밀번호가 맞아도 잠금 중이면 거부.
        AccountLockService.LockStatus lock = lockService.checkLock(user.getId());
        if (lock.locked()) {
            throw new AccountLockedException(lock.unlockAt());
        }

        boolean matches = passwordHasher.matches(rawPassword, user.getPasswordHash());
        if (!matches) {
            AccountLockService.LockTriggered triggered = lockService.recordFailure(user);
            if (triggered.triggered()) {
                throw new AccountLockedException(triggered.unlockAt());
            }
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        // 통과: 카운터/잠금 해제 + 마지막 로그인 시각 갱신은 향후 추가.
        lockService.recordSuccess(user);
        return user;
    }

    /**
     * AccountLocked 만 별도 타입으로 두는 이유:
     * REST 응답에 {@code unlockAt} 을 함께 실어야 해서 BusinessException 만으론 부족하다.
     * Controller advice 에서 이 예외를 따로 잡아 423 응답으로 매핑한다.
     */
    public static class AccountLockedException extends BusinessException {
        private final OffsetDateTime unlockAt;
        public AccountLockedException(OffsetDateTime unlockAt) {
            super(ErrorCode.ACCOUNT_LOCKED);
            this.unlockAt = unlockAt;
        }
        public OffsetDateTime unlockAt() { return unlockAt; }
    }
}
