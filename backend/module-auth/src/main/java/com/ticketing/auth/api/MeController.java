package com.ticketing.auth.api;

import com.ticketing.auth.api.dto.MeResponse;
import com.ticketing.auth.security.AuthPrincipal;
import com.ticketing.auth.security.AuthenticatedUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 현재 인증된 사용자 프로필 조회.
 *
 * <p>
 *   JWT 클레임을 그대로 반환 — 추가 DB 조회 없이 답하는 게 의도(빠르고 캐시 친화적).
 *   만약 name/roles 가 DB 와 어긋날 가능성이 있다면 별도 /me/refresh 같은 엔드포인트로
 *   강제 재조회를 노출하는 게 좋다 (현 단계 spec 에는 없음).
 * </p>
 */
@RestController
@RequestMapping("/api/v1")
public class MeController {

    @GetMapping("/me")
    public MeResponse me(@AuthenticatedUser AuthPrincipal me) {
        return new MeResponse(me.userId(), me.email(), me.name(), me.roles());
    }
}
