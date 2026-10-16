package com.ticketing.common.web;

import com.ticketing.common.error.ApiErrorBody;
import com.ticketing.common.error.BusinessException;
import com.ticketing.common.error.ErrorCode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.validation.method.ParameterValidationResult;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 모든 모듈 공통 오류 → HTTP 응답 매핑. 본문은 항상 {@link ApiErrorBody} 모양.
 *
 * <h2>무엇을 잡나</h2>
 * <pre>
 *   BusinessException                         → ErrorCode.httpStatus()
 *   본문·파라미터 검증 실패                    → 400 VALIDATION_ERROR { fields }
 *   형식이 틀린 본문·파라미터 (UUID, 숫자 등)    → 400 INVALID_REQUEST
 *   DB 유일 제약 위반 (SQLState 23505)          → 409 DUPLICATE_RESOURCE
 *   DB 길이·범위·검사 제약 위반 (22xxx, 23514 등) → 400 INVALID_REQUEST
 *   스프링 MVC 표준 오류 (404·405·415 …)       → 그 상태 + 상태별 공통 코드
 *   그 밖의 예외                               → 500 INTERNAL_ERROR (원인은 로그에만)
 * </pre>
 *
 * <p>
 *   예전에는 모듈마다 advice 가 BusinessException 상태를 따로 정해 같은 코드가 404/400 으로
 *   갈렸고, 검증 실패는 auth 모듈만 {@code fields} 를 줬으며, DB 제약 위반은 500 이었다
 *   (2026-10-13 QA P2). 모듈 advice 에는 이제 그 모듈만의 특수 처리만 남는다.
 * </p>
 *
 * <p>
 *   {@code basePackages} 없이 가장 낮은 우선순위라, 모듈 advice 가 먼저 기회를 갖는다.
 *   보안 예외(@PreAuthorize 거부 등)는 다시 던져 Spring Security 의 401/403 처리기로 넘긴다.
 * </p>
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class ApiErrorAdvice {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorAdvice.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> business(BusinessException ex) {
        return ResponseEntity.status(ex.code().httpStatus()).body(ApiErrorBody.of(ex));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalidBody(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            // 같은 필드에 여러 위반이 있어도 첫 번째만 기록(응답 단순화).
            fields.putIfAbsent(fe.getField(), fe.getDefaultMessage());
        }
        return validation(fields);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Map<String, Object>> invalidParameter(HandlerMethodValidationException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            String name = result.getMethodParameter().getParameterName();
            if (name == null) name = "arg" + result.getMethodParameter().getParameterIndex();
            if (!result.getResolvableErrors().isEmpty()) {
                fields.putIfAbsent(name, result.getResolvableErrors().get(0).getDefaultMessage());
            }
        }
        return validation(fields);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> constraintViolation(ConstraintViolationException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (ConstraintViolation<?> v : ex.getConstraintViolations()) {
            fields.putIfAbsent(String.valueOf(v.getPropertyPath()), v.getMessage());
        }
        return validation(fields);
    }

    /** 본문 JSON 이 깨졌거나, UUID·숫자·enum 자리에 엉뚱한 값이 왔을 때. 내부 파서 메시지는 숨긴다. */
    @ExceptionHandler({HttpMessageNotReadableException.class, TypeMismatchException.class})
    public ResponseEntity<Map<String, Object>> unreadable(Exception ex) {
        return respond(ErrorCode.INVALID_REQUEST);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> fallback(Exception ex) throws Exception {
        if (isSecurityException(ex) || ex instanceof AsyncRequestNotUsableException) {
            throw ex;
        }
        SQLException sql = findSqlException(ex);
        if (sql != null && sql.getSQLState() != null) {
            ErrorCode code = fromSqlState(sql.getSQLState());
            if (code != null) {
                log.info("DB 제약 위반을 {} 로 응답: sqlState={}", code, sql.getSQLState());
                return respond(code);
            }
        }
        if (ex instanceof ErrorResponse er) {
            int status = er.getStatusCode().value();
            if (status < 500) {
                return ResponseEntity.status(status).body(withStatus(fromHttpStatus(status), status));
            }
        }
        log.error("처리하지 못한 예외", ex);
        return respond(ErrorCode.INTERNAL_ERROR);
    }

    private static ResponseEntity<Map<String, Object>> validation(Map<String, String> fields) {
        Map<String, Object> body = ApiErrorBody.of(ErrorCode.VALIDATION_ERROR);
        body.put("fields", fields);
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.httpStatus()).body(body);
    }

    private static ResponseEntity<Map<String, Object>> respond(ErrorCode code) {
        return ResponseEntity.status(code.httpStatus()).body(ApiErrorBody.of(code));
    }

    /** 415·406 처럼 대응 코드가 따로 없는 상태는 코드는 공통값, status 는 실제 값으로 둔다. */
    private static Map<String, Object> withStatus(ErrorCode code, int status) {
        Map<String, Object> body = ApiErrorBody.of(code);
        body.put("status", status);
        return body;
    }

    private static ErrorCode fromHttpStatus(int status) {
        return switch (status) {
            case 401 -> ErrorCode.AUTHENTICATION_REQUIRED;
            case 403 -> ErrorCode.FORBIDDEN;
            case 404 -> ErrorCode.NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 429 -> ErrorCode.RATE_LIMIT_EXCEEDED;
            default -> ErrorCode.INVALID_REQUEST;
        };
    }

    /**
     * PostgreSQL SQLState → 응답 코드. 모르는 상태(교착·직렬화 실패 등)는 null 로 돌려 500 으로 둔다.
     * 23505 unique / 23503 FK / 23502 NOT NULL / 23514 CHECK / 22xxx 값 오류(길이 초과 22001, 범위 22003 …).
     */
    static ErrorCode fromSqlState(String state) {
        if ("23505".equals(state)) return ErrorCode.DUPLICATE_RESOURCE;
        if ("23503".equals(state) || "23502".equals(state) || "23514".equals(state)) return ErrorCode.INVALID_REQUEST;
        if (state.startsWith("22")) return ErrorCode.INVALID_REQUEST;
        return null;
    }

    private static SQLException findSqlException(Throwable ex) {
        for (Throwable t = ex; t != null && t.getCause() != t; t = t.getCause()) {
            if (t instanceof SQLException sql) return sql;
        }
        return null;
    }

    /** common-domain 은 spring-security 에 기대지 않으므로 패키지 이름으로 판별한다. */
    private static boolean isSecurityException(Throwable ex) {
        return ex.getClass().getName().startsWith("org.springframework.security.");
    }
}
