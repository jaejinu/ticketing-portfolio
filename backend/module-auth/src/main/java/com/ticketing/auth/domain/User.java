package com.ticketing.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 회원 엔티티.
 *
 * <h2>책임</h2>
 * <ul>
 *   <li>회원 식별(id, email)</li>
 *   <li>BCrypt 해시된 비밀번호 보관</li>
 *   <li>권한(roles) 배열 — postgres TEXT[] 컬럼에 매핑</li>
 *   <li>로그인 실패/잠금 상태 추적</li>
 * </ul>
 *
 * <h2>설계 결정</h2>
 * <ul>
 *   <li><b>UUID PK</b> : 외부 노출 시 회원 수가 새지 않도록.</li>
 *   <li><b>email lowercase 정규화</b> : 가입/로그인 양쪽에서 service 레이어가 lower-case 후 저장/조회.</li>
 *   <li><b>roles 는 TEXT[]</b> : Hibernate {@link JdbcTypeCode}({@code Types.ARRAY}) 로 매핑.</li>
 *   <li>setter 는 의도적으로 좁게(필드별) 노출 — application service 만 변경 가능하도록.</li>
 * </ul>
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "email", nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false, columnDefinition = "text")
    private String passwordHash;

    @Column(name = "name", length = 100)
    private String name;

    /**
     * Postgres {@code TEXT[]} 컬럼 매핑.
     * Hibernate 6 의 {@link JdbcTypeCode}({@link SqlTypes#ARRAY}) 를 사용한다.
     */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "roles", columnDefinition = "text[]", nullable = false)
    private String[] roles;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private UserStatus status;

    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount;

    @Column(name = "locked_until")
    private OffsetDateTime lockedUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** JPA 용 기본 생성자. 직접 호출 금지. */
    protected User() {
    }

    private User(UUID id, String email, String passwordHash, String name,
                 String[] roles, UserStatus status) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.name = name;
        this.roles = roles;
        this.status = status;
        this.failedLoginCount = 0;
        this.lockedUntil = null;
    }

    /**
     * 신규 회원 팩토리.
     * UUID 는 v4 사용(시간 정렬 필요 없음, 보안성 충분).
     */
    public static User newUser(String email, String passwordHash, String name) {
        return new User(
                UUID.randomUUID(),
                email,
                passwordHash,
                name,
                new String[]{"USER"},
                UserStatus.ACTIVE);
    }

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    // ---- 도메인 동작 ---------------------------------------------------------

    /** 로그인 성공: 실패 카운터/잠금 해제. */
    public void markLoginSuccess() {
        this.failedLoginCount = 0;
        this.lockedUntil = null;
        if (this.status == UserStatus.LOCKED) {
            this.status = UserStatus.ACTIVE;
        }
    }

    /**
     * 실패 카운트 누적 후 임계치를 넘으면 잠금 처리.
     * @return 잠금이 발동되었는지 여부 (true=이번 호출로 LOCKED 진입)
     */
    public boolean recordLoginFailure(int threshold, OffsetDateTime lockedUntilWhenLocked) {
        this.failedLoginCount += 1;
        if (this.failedLoginCount >= threshold) {
            this.status = UserStatus.LOCKED;
            this.lockedUntil = lockedUntilWhenLocked;
            return true;
        }
        return false;
    }

    /** 운영자 unlock — 잠금 즉시 해제. */
    public void unlock() {
        this.status = UserStatus.ACTIVE;
        this.failedLoginCount = 0;
        this.lockedUntil = null;
    }

    /**
     * 현 시점 기준 "잠금 중" 인지 판단.
     * status=LOCKED 이고 locked_until 이 미래라면 잠금.
     * locked_until 이 과거면 자동 해제 대상(서비스 레이어에서 unlock 호출).
     */
    public boolean isLockedAt(OffsetDateTime now) {
        return status == UserStatus.LOCKED
                && lockedUntil != null
                && lockedUntil.isAfter(now);
    }

    // ---- getters -------------------------------------------------------------

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getName() { return name; }
    public List<String> getRoles() {
        return roles == null ? Collections.emptyList() : new ArrayList<>(Arrays.asList(roles));
    }
    public UserStatus getStatus() { return status; }
    public int getFailedLoginCount() { return failedLoginCount; }
    public OffsetDateTime getLockedUntil() { return lockedUntil; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
