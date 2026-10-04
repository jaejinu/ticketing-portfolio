package com.ticketing.auth.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * 회원 영속화 인터페이스 (Spring Data JPA).
 *
 * <p>
 *   회원 조회 키는 두 가지 — userId(UUID), email(String) — 만 노출.
 *   이메일은 lowercase 정규화된 형태로 조회한다(서비스 레이어가 보장).
 * </p>
 */
@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
