package com.ticketing.queue.api.macro;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.common.error.ErrorCode;
import com.ticketing.queue.api.scope.RequestScope;
import com.ticketing.queue.application.QueueProperties;
import com.ticketing.queue.application.macro.MacroDetectionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 매크로 탐지 필터 — RateLimit 뒤, Security 앞에 삽입.
 *
 * <h2>왜 별도 필터인가</h2>
 * <ul>
 *   <li>관심사 분리: rate limit(빈도) vs macro(패턴)은 판정 근거가 다르고 진화 주기도 다르다.</li>
 *   <li>순서 명확: rate limit 이 먼저 걸러낸 뒤 통과한 트래픽만 매크로 탐지에 도달 →
 *       Redis 접근을 아낀다(rate limit 탈락은 매크로 계산 없이 즉시 종료).</li>
 * </ul>
 *
 * <h2>동작</h2>
 * <ol>
 *   <li>해당 스코프+키가 이미 차단 목록에 있으면 즉시 403 + code=MACRO_DETECTED.</li>
 *   <li>차단 목록에 없으면 요청 timestamp 를 슬라이딩 윈도우에 기록.</li>
 *   <li>윈도우가 다 찼으면 봇 점수 계산 → 임계값 초과 시 차단 마커 SET EX + 이번 요청 403.</li>
 *   <li>임계값 이하면 chain 진행.</li>
 * </ol>
 *
 * <h2>사용자 응답 모호화</h2>
 * <p>
 *   {@link com.ticketing.queue.error.QueueErrorAdvice} 주석의 지침("사용자에게 노출 시 사유 모호화 권장") 을
 *   따라 body 에는 봇 점수/증거를 <b>노출하지 않는다</b> — 매크로 제작자가 우회 학습에 활용하는 걸 막기 위함.
 *   운영자는 서버 로그(INFO)에서 상세 확인.
 * </p>
 */
public class MacroDetectionFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(MacroDetectionFilter.class);

    public static final String PATH_PATTERN = "/api/v1/queue/**";

    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final MacroDetectionService service;
    private final QueueProperties.MacroDetection props;
    private final ObjectMapper objectMapper;
    private final RequestScope requestScope;

    public MacroDetectionFilter(MacroDetectionService service,
                                QueueProperties.MacroDetection props,
                                ObjectMapper objectMapper, RequestScope requestScope) {
        this.service = service;
        this.props = props;
        this.objectMapper = objectMapper;
        this.requestScope = requestScope;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!props.isEnabled()) return true;
        return !pathMatcher.match(PATH_PATTERN, request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        RequestScope.Resolved resolved = requestScope.resolve(request);
        String scope = resolved.scope();
        String key = resolved.key();

        if (service.isBlocked(scope, key)) {
            log.debug("macro blocked (existing) scope={} key={}", scope, key);
            writeForbidden(response);
            return;
        }

        String ua = request.getHeader(HttpHeaders.USER_AGENT);
        MacroDetectionService.Decision decision = service.recordAndEvaluate(scope, key, ua);
        if (decision.blocked()) {
            writeForbidden(response);
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * 403 응답 작성. body 에는 최소 정보만.
     *
     * <p>
     *   Retry-After 는 넣지 않는다 — 봇에게 재시도 가능 시각을 알려주는 셈이 되기 때문.
     *   차단은 서버 로그의 blockDuration 을 참고해 운영자가 관리한다.
     * </p>
     */
    private void writeForbidden(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", ErrorCode.MACRO_DETECTED.name());
        body.put("message", ErrorCode.MACRO_DETECTED.defaultMessage());
        // scope / botScore / 증거는 의도적으로 노출하지 않음 — 봇 우회 학습 방지.

        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
