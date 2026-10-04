package com.ticketing.show.domain;

/**
 * 좌석 등급.
 *
 * <p>
 *   VIP > R > S > A. base_price 는 등급과 별개로 자유롭게 설정 가능하지만 일반적으로 위 순서를 따른다.
 *   PostgreSQL 컬럼에는 VARCHAR(20) + CHECK 제약으로 저장.
 * </p>
 */
public enum SectionGrade {
    VIP,
    R,
    S,
    A
}
