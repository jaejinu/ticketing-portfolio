package com.ticketing.alert.application;

import com.ticketing.alert.domain.Alert;
import com.ticketing.alert.domain.AlertRepository;
import com.ticketing.alert.index.AlertIndex;
import com.ticketing.common.error.BusinessException;
import com.ticketing.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 알람 CRUD 서비스.
 *
 * <h2>인덱스 정합</h2>
 * <p>
 *   create / disable 시 DB 변경과 Redis 인덱스 갱신을 같은 TX 안에서 호출한다.
 *   Redis 호출은 외부 인프라라 정확한 commit-and-do 패턴이 더 안전하지만, 본 PR 은 단순화 —
 *   TX 안에서 호출하고 Redis 장애 시 TX 자체 rollback (Phase 7b 에서 TransactionalEventListener
 *   기반 commit-after-update 패턴 도입 검토).
 * </p>
 */
@Service
public class AlertCommandService {

    private static final Logger log = LoggerFactory.getLogger(AlertCommandService.class);

    private final AlertRepository alertRepository;
    private final AlertIndex alertIndex;

    public AlertCommandService(AlertRepository alertRepository, AlertIndex alertIndex) {
        this.alertRepository = alertRepository;
        this.alertIndex = alertIndex;
    }

    @Transactional
    public Alert create(UUID userId, UUID scheduleId, UUID sectionId,
                         String type, long thresholdPrice, String channel) {
        Alert alert = Alert.create(userId, scheduleId, sectionId, type, thresholdPrice, channel);
        // ID 를 직접 할당하는 엔티티라 save() 가 merge 경로를 탄다 — @PrePersist(createdAt 세팅)는
        // 병합된 "관리 인스턴스" 에만 적용되므로, 원본이 아닌 save() 반환값을 써야 한다.
        // (원본을 반환하면 createdAt=null 인 응답이 나가 클라이언트 스키마 검증이 깨진다.)
        Alert saved = alertRepository.save(alert);
        alertIndex.addActive(sectionId, saved.getId());
        log.debug("alert created: id={}, user={}, section={}, threshold={}",
                saved.getId(), userId, sectionId, thresholdPrice);
        return saved;
    }

    @Transactional
    public Alert disable(UUID alertId, UUID requesterId) {
        Alert alert = alertRepository.findById(alertId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ALERT_NOT_FOUND,
                        "알람을 찾을 수 없습니다. id=" + alertId));
        boolean wasActive = alert.isActive();
        alert.disableByUser(requesterId);
        if (wasActive) {
            alertIndex.remove(alert.getSectionId(), alert.getId());
        }
        return alert;
    }

    @Transactional(readOnly = true)
    public Alert getMyAlert(UUID alertId, UUID requesterId) {
        Alert alert = alertRepository.findById(alertId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ALERT_NOT_FOUND,
                        "알람을 찾을 수 없습니다. id=" + alertId));
        if (!alert.getUserId().equals(requesterId)) {
            throw new BusinessException(ErrorCode.SHOW_ACCESS_DENIED,
                    "본인 알람만 조회할 수 있습니다.");
        }
        return alert;
    }

    @Transactional(readOnly = true)
    public List<Alert> listMine(UUID userId) {
        return alertRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }
}
