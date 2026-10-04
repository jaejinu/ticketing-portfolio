package com.ticketing.auth.domain;

/**
 * 회원 계정 상태.
 *
 * <ul>
 *   <li>{@link #ACTIVE}  — 정상 사용 가능.</li>
 *   <li>{@link #LOCKED}  — 잠금. 5회 연속 실패로 자동 잠금되었거나 운영자 수동 잠금.
 *       잠금 만료(locked_until) 가 지났거나 운영자 unlock 호출로 해제된다.</li>
 *   <li>{@link #DELETED} — 탈퇴/관리자 영구 정지. 로그인 절대 불가.</li>
 * </ul>
 *
 * <p>
 *   PostgreSQL enum 대신 VARCHAR(20) + CHECK 제약으로 표현한다.
 *   추후 상태 추가 시 migration 만 추가하면 되고 JPA enum 매핑이 단순해진다.
 * </p>
 */
public enum UserStatus {
    ACTIVE,
    LOCKED,
    DELETED
}
