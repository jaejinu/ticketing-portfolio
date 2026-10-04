/**
 * module-show — 공연 / 회차 / 구역 / 좌석 도메인.
 *
 * <h2>책임 (Phase 2 범위)</h2>
 * <ul>
 *   <li>{@link com.ticketing.show.domain.Show} / {@link com.ticketing.show.domain.ShowSchedule} /
 *       {@link com.ticketing.show.domain.Section} / {@link com.ticketing.show.domain.Seat} CRUD</li>
 *   <li>주최자 콘솔 API: 공연/회차/구역 등록, 좌석 일괄 등록, 공연 publish</li>
 *   <li>공개 API: 공연 목록/상세/구역/좌석 스냅샷 조회</li>
 *   <li>데모 시드: SeedRunner 가 organizer + 공연 + 400 좌석을 idempotent 하게 생성</li>
 * </ul>
 *
 * <h2>도메인 불변식</h2>
 * <ul>
 *   <li>{@code sales_start_at} 이후엔 section.base_price 변경 불가</li>
 *   <li>show.status: DRAFT → PUBLISHED → CLOSED (역방향 불가)</li>
 *   <li>seat.status: short string ("AVAILABLE", "HELD", "SOLD") — JPA enum 미사용</li>
 * </ul>
 *
 * <h2>다른 모듈과의 경계</h2>
 * <ul>
 *   <li>module-auth.User / UserRepository 를 임시로 참조 (RoleAdminController + SeedRunner)
 *       — Phase 8 에 admin 컨트롤러는 module-auth 로 이동 예정</li>
 *   <li>좌석 hold/sell 전이는 module-seat 책임. module-show 는 메타 + 초기 상태만 책임.</li>
 *   <li>현재가(currentPrice) 는 module-pricing 책임. 본 모듈은 base_price 만 노출.</li>
 * </ul>
 */
package com.ticketing.show;
