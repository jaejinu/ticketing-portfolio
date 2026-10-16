package com.ticketing.auth.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import com.ticketing.auth.security.ClientIpResolver;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 관리자·조직자 행동 기록 ({@code admin_audit_log}).
 *
 * <p>
 *   호출자의 트랜잭션 안에서 INSERT 한다 — 바뀐 내용과 기록이 함께 커밋되거나 함께 취소된다.
 *   클라이언트 IP 는 {@link ClientIpResolver} — 인증 프록시가 넘긴 방문자 IP(공유 비밀 확인) 또는 remote address.
 * </p>
 */
@Component
public class AuditRecorder {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ClientIpResolver clientIp;

    public AuditRecorder(JdbcTemplate jdbc, ObjectMapper json, ClientIpResolver clientIp) {
        this.jdbc = jdbc;
        this.json = json;
        this.clientIp = clientIp;
    }

    public void record(UUID actorId, String action, String targetType, Object targetId, Map<String, ?> detail) {
        jdbc.update("""
                INSERT INTO admin_audit_log (id, actor_id, action, target_type, target_id, detail, client_ip)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                """, UUID.randomUUID(), actorId, action, targetType, String.valueOf(targetId),
                json.writeValueAsString(detail == null ? Map.of() : detail), clientIp.current());
    }

    /** 최근 기록. actorId / targetId 가 주어지면 그 조건으로 좁힌다. */
    public List<Entry> recent(int limit, UUID actorId, String targetId) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, actor_id, action, target_type, target_id, detail::text, client_ip, created_at
                  FROM admin_audit_log WHERE 1 = 1""");
        List<Object> args = new ArrayList<>();
        if (actorId != null) {
            sql.append(" AND actor_id = ?");
            args.add(actorId);
        }
        if (targetId != null && !targetId.isBlank()) {
            sql.append(" AND target_id = ?");
            args.add(targetId);
        }
        sql.append(" ORDER BY created_at DESC LIMIT ?");
        args.add(Math.max(1, Math.min(limit, 500)));
        return jdbc.query(sql.toString(), (rs, i) -> new Entry(
                rs.getObject("id", UUID.class),
                rs.getObject("actor_id", UUID.class),
                rs.getString("action"),
                rs.getString("target_type"),
                rs.getString("target_id"),
                json.readTree(rs.getString("detail")),
                rs.getString("client_ip"),
                rs.getObject("created_at", OffsetDateTime.class)), args.toArray());
    }

    public record Entry(UUID id, UUID actorId, String action, String targetType, String targetId,
                        JsonNode detail, String clientIp, OffsetDateTime createdAt) {}
}
