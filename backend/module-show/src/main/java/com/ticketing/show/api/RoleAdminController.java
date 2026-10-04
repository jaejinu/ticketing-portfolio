package com.ticketing.show.api;

import com.ticketing.auth.domain.User;
import com.ticketing.auth.domain.UserRepository;
import com.ticketing.common.error.BusinessException;
import com.ticketing.common.error.ErrorCode;
import com.ticketing.show.api.dto.RoleChangeRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 회원 권한 부여/회수 운영자 엔드포인트.
 *
 * <h2>매핑</h2>
 * <ul>
 *   <li>POST /api/v1/admin/users/{userId}/roles — ADD/REMOVE 액션</li>
 * </ul>
 *
 * <p>
 *   <b>TODO (Phase 8):</b> 본 컨트롤러는 module-auth 로 이동해야 한다.
 *   현재는 module-auth 가 이미 완료 상태라 신규 컨트롤러 추가가 충돌 위험이 있어
 *   임시로 module-show 에 둔다. {@link User} / {@link UserRepository} 를 직접 import 한다.
 * </p>
 *
 * <p>
 *   roles 변경 도메인 메서드가 없어 reflection 으로 필드 교체. Phase 8 에서 User#addRole/removeRole
 *   추가 후 reflection 제거 예정.
 * </p>
 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
public class RoleAdminController {

    private final UserRepository userRepository;

    public RoleAdminController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @PostMapping("/api/v1/admin/users/{userId}/roles")
    @Transactional
    public ResponseEntity<Object> changeRole(@PathVariable UUID userId,
                                              @Valid @RequestBody RoleChangeRequest req) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST,
                        "사용자를 찾을 수 없습니다. userId=" + userId));

        Set<String> roles = new LinkedHashSet<>(user.getRoles());
        if ("ADD".equals(req.action())) {
            roles.add(req.role());
        } else { // REMOVE — Bean Validation 에서 ADD|REMOVE 만 허용.
            roles.remove(req.role());
        }
        setRolesViaReflection(user, roles.toArray(new String[0]));
        userRepository.save(user);

        return ResponseEntity.ok(java.util.Map.of(
                "userId", user.getId().toString(),
                "roles", List.copyOf(roles)));
    }

    /** module-auth.User 의 roles 필드를 reflection 으로 교체. Phase 8 에 도메인 메서드로 대체. */
    private void setRolesViaReflection(User user, String[] roles) {
        try {
            Field f = User.class.getDeclaredField("roles");
            f.setAccessible(true);
            f.set(user, roles);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("User.roles 필드 reflection 실패", ex);
        }
    }
}
