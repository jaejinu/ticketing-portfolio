package com.ticketing.auth.application;

import com.ticketing.auth.domain.PasswordHasher;
import com.ticketing.auth.domain.User;
import com.ticketing.auth.domain.UserRepository;
import com.ticketing.common.error.BusinessException;
import com.ticketing.common.error.ErrorCode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * 회원가입 유스케이스.
 *
 * <h2>흐름</h2>
 * <ol>
 *   <li>email lowercase 정규화</li>
 *   <li>existsByEmail → 중복 시 {@link ErrorCode#EMAIL_CONFLICT}</li>
 *   <li>BCrypt 해시 + {@link User#newUser} → 저장</li>
 * </ol>
 *
 * <h2>동시 가입 race condition</h2>
 * existsByEmail 체크와 INSERT 사이에 다른 트랜잭션이 같은 이메일을 가입할 수 있다.
 * 이 경우 DB unique 제약이 {@link DataIntegrityViolationException} 으로 fallback → EMAIL_CONFLICT 매핑.
 */
@Service
public class SignupService {

    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;

    public SignupService(UserRepository userRepository, PasswordHasher passwordHasher) {
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
    }

    @Transactional
    public User signup(String email, String rawPassword, String name) {
        String normalizedEmail = email.toLowerCase(Locale.ROOT).trim();
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new BusinessException(ErrorCode.EMAIL_CONFLICT);
        }
        try {
            String hash = passwordHasher.hash(rawPassword);
            User user = User.newUser(normalizedEmail, hash, name);
            return userRepository.save(user);
        } catch (DataIntegrityViolationException dup) {
            throw new BusinessException(ErrorCode.EMAIL_CONFLICT, ErrorCode.EMAIL_CONFLICT.defaultMessage(), null, dup);
        }
    }
}
