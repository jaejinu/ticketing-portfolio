package com.ticketing.show.domain;

import com.ticketing.common.error.BusinessException;
import com.ticketing.common.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * 공연 마스터 엔티티.
 *
 * <h2>책임</h2>
 * <ul>
 *   <li>공연의 메타 정보(title, venue, description, posterUrl) 보관</li>
 *   <li>주최자(organizerId) 식별 — users.id (UUID) 외래키</li>
 *   <li>공개 상태(status) 전이 — DRAFT → PUBLISHED → CLOSED 단방향</li>
 * </ul>
 *
 * <h2>불변식</h2>
 * <ul>
 *   <li>status 는 도메인 메서드({@link #publish()}, {@link #close()}) 로만 변경 가능</li>
 *   <li>역방향 전이(PUBLISHED → DRAFT, CLOSED → *) 시도 시 {@link BusinessException} 발생</li>
 *   <li>organizerId 는 생성 후 변경 불가 (소유권 이전은 별도 도메인 액션 필요)</li>
 * </ul>
 */
@Entity
@Table(name = "shows")
public class Show {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "organizer_id", nullable = false, updatable = false)
    private UUID organizerId;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "venue", nullable = false, length = 200)
    private String venue;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "poster_url", columnDefinition = "text")
    private String posterUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ShowStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    /** JPA 용 기본 생성자. 직접 호출 금지. */
    protected Show() {
    }

    private Show(UUID id, UUID organizerId, String title, String venue,
                 String description, String posterUrl, ShowStatus status) {
        this.id = id;
        this.organizerId = organizerId;
        this.title = title;
        this.venue = venue;
        this.description = description;
        this.posterUrl = posterUrl;
        this.status = status;
    }

    /** 신규 공연 팩토리. 상태는 DRAFT 로 시작. */
    public static Show create(UUID organizerId, String title, String venue,
                              String description, String posterUrl) {
        return new Show(UUID.randomUUID(), organizerId, title, venue,
                description, posterUrl, ShowStatus.DRAFT);
    }

    /** 시드 등에서 id 를 고정해야 할 때 사용. 일반 코드에서는 {@link #create} 사용. */
    public static Show createWithId(UUID id, UUID organizerId, String title, String venue,
                                    String description, String posterUrl, ShowStatus status) {
        return new Show(id, organizerId, title, venue, description, posterUrl, status);
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

    /** 공연 메타 수정 (status 는 별도 전이). */
    public void updateMeta(String title, String venue, String description, String posterUrl) {
        if (title != null) this.title = title;
        if (venue != null) this.venue = venue;
        if (description != null) this.description = description;
        if (posterUrl != null) this.posterUrl = posterUrl;
    }

    /** DRAFT → PUBLISHED. 이미 PUBLISHED 면 멱등하게 통과. 그 외 상태에선 거부. */
    public void publish() {
        if (this.status == ShowStatus.PUBLISHED) return;
        if (this.status != ShowStatus.DRAFT) {
            // ShowStatus 전이 위반 → INVALID_SHOW_STATE (HTTP 409 Conflict).
            // INVALID_REQUEST (400) 와 구분: "요청 형식은 맞지만 도메인 상태가 허용 안 함".
            throw new BusinessException(
                    ErrorCode.INVALID_SHOW_STATE,
                    "DRAFT 상태에서만 publish 가능합니다. 현재=" + this.status);
        }
        this.status = ShowStatus.PUBLISHED;
    }

    /** PUBLISHED → CLOSED. DRAFT 에서도 닫을 수 있다(공연 자체 취소). */
    public void close() {
        if (this.status == ShowStatus.CLOSED) return;
        this.status = ShowStatus.CLOSED;
    }

    // ---- getters -------------------------------------------------------------

    public UUID getId() { return id; }
    public UUID getOrganizerId() { return organizerId; }
    public String getTitle() { return title; }
    public String getVenue() { return venue; }
    public String getDescription() { return description; }
    public String getPosterUrl() { return posterUrl; }
    public ShowStatus getStatus() { return status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
