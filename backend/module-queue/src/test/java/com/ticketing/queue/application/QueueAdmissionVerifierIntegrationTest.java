package com.ticketing.queue.application;

import com.ticketing.queue.QueueIntegrationTestBase;
import com.ticketing.queue.QueueIntegrationTestApp;
import com.ticketing.seat.application.QueueAdmissionVerifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 좌석 점유가 묻는 "이 사용자가 이 회차에 입장했나" 가 사용자·회차 단위로 정확한지.
 * 입장 키가 ticket 단위뿐이던 시절에는 누가 입장했는지 알 수 없어 좌석 API 가 대기열을 검사하지 못했다.
 */
@SpringBootTest(classes = QueueIntegrationTestApp.class)
class QueueAdmissionVerifierIntegrationTest extends QueueIntegrationTestBase {

    @Autowired VirtualQueueService queueService;
    @Autowired QueueAdmissionVerifier verifier;

    @Test
    @DisplayName("대기 중에는 입장 아님 → admit 후 그 사용자·그 회차만 입장")
    void admissionIsScopedToUserAndSchedule() {
        UUID schedule = UUID.randomUUID();
        UUID otherSchedule = UUID.randomUUID();
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();

        queueService.enqueue(schedule, userA.toString());
        queueService.enqueue(schedule, userB.toString());
        assertThat(verifier.isAdmitted(schedule, userA)).isFalse();

        // 앞에서 1명만 입장 — 먼저 줄 선 A.
        assertThat(queueService.admitHead(schedule, 1)).isEqualTo(1);

        assertThat(verifier.isAdmitted(schedule, userA)).isTrue();
        assertThat(verifier.isAdmitted(schedule, userB)).isFalse();
        assertThat(verifier.isAdmitted(otherSchedule, userA)).isFalse();
    }
}
