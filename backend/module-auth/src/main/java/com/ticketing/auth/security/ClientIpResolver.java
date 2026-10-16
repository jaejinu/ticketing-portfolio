package com.ticketing.auth.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Pattern;

/**
 * 요청을 보낸 실제 클라이언트 IP.
 *
 * <h2>왜 remoteAddr 만으로는 안 되는가</h2>
 * <p>
 *   공개 데모의 로그인·가입은 브라우저 → Vercel 서버(Next 인증 프록시) → 터널 → 백엔드로 온다.
 *   백엔드가 보는 주소는 모든 방문자에게 같은 Vercel 출구 IP 라, IP 단위 요청 제한·잠금을 걸면
 *   전원이 함께 막힌다. 그래서 프록시가 방문자 IP 를 {@value #CLIENT_IP_HEADER} 로 넘기고,
 *   공유 비밀({@value #PROXY_TOKEN_HEADER})이 맞을 때만 그 값을 믿는다. 비밀이 설정되지 않았거나
 *   틀리면 헤더는 무시된다 — 아무나 헤더를 붙여 IP 를 바꿀 수 없다.
 * </p>
 */
@Component
public class ClientIpResolver {

    public static final String CLIENT_IP_HEADER = "X-Client-Ip";
    public static final String PROXY_TOKEN_HEADER = "X-Proxy-Token";
    private static final Pattern IP = Pattern.compile("^[0-9A-Fa-f:.]{2,45}$");

    private final byte[] proxySecret;

    public ClientIpResolver(@Value("${app.auth.proxy.secret:${AUTH_PROXY_SECRET:}}") String proxySecret) {
        this.proxySecret = proxySecret.getBytes(StandardCharsets.UTF_8);
    }

    public String resolve(HttpServletRequest request) {
        if (proxySecret.length > 0) {
            String token = request.getHeader(PROXY_TOKEN_HEADER);
            String forwarded = request.getHeader(CLIENT_IP_HEADER);
            if (token != null && forwarded != null && IP.matcher(forwarded).matches()
                    && MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), proxySecret)) {
                return forwarded;
            }
        }
        return request.getRemoteAddr();
    }

    /** 현재 요청 스레드의 클라이언트 IP. 요청 밖(스케줄러 등)이면 null. */
    public String current() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return resolve(attrs.getRequest());
        }
        return null;
    }
}
