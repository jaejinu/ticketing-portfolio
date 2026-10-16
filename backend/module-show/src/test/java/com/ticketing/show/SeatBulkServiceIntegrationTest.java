package com.ticketing.show;

import com.ticketing.auth.domain.User;
import com.ticketing.auth.domain.UserRepository;
import com.ticketing.common.error.BusinessException;
import com.ticketing.common.error.ErrorCode;
import com.ticketing.show.application.SeatBulkService;
import com.ticketing.show.domain.Section;
import com.ticketing.show.domain.SectionGrade;
import com.ticketing.show.domain.SectionRepository;
import com.ticketing.show.domain.SeatRepository;
import com.ticketing.show.domain.Show;
import com.ticketing.show.domain.ShowRepository;
import com.ticketing.show.domain.ShowSchedule;
import com.ticketing.show.domain.ShowScheduleRepository;
import com.ticketing.show.domain.ShowStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * 좌석 일괄 등록 입력 검증.
 * 2026-10-13 QA: rows=3, cols=1,431,655,766 이 int 곱셈 넘침으로 5,000석 상한을 통과해
 * OutOfMemoryError 를 냈다. 조직자 한 명이 서버 전체를 멈출 수 있던 경로.
 */
class SeatBulkServiceIntegrationTest extends ShowIntegrationTestBase {

    @Autowired SeatBulkService seatBulkService;
    @Autowired UserRepository userRepository;
    @Autowired ShowRepository showRepository;
    @Autowired ShowScheduleRepository scheduleRepository;
    @Autowired SectionRepository sectionRepository;
    @Autowired SeatRepository seatRepository;

    private UUID organizerId;
    private UUID sectionId;

    @BeforeEach
    void seed() {
        seatRepository.deleteAllInBatch();
        sectionRepository.deleteAllInBatch();
        scheduleRepository.deleteAllInBatch();
        showRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();

        User organizer = userRepository.save(User.newUser("organizer-bulk@example.com",
                "$2a$04$test.hash.placeholder.value.for.it.only.xxxxxxxxxxxxxxxxxxxxxx", "Bulk IT"));
        organizerId = organizer.getId();
        Show show = showRepository.save(Show.createWithId(UUID.randomUUID(), organizerId,
                "[IT] Bulk", "IT Hall", null, null, ShowStatus.DRAFT));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        ShowSchedule schedule = scheduleRepository.save(ShowSchedule.create(show.getId(),
                now.plusDays(7), now.plusDays(7).plusHours(2), now.plusDays(3)));
        sectionId = sectionRepository.save(Section.create(schedule.getId(), "R", SectionGrade.R, 50_000L)).getId();
    }

    @Test
    @DisplayName("곱셈이 int 를 넘는 입력은 바로 거절 — 객체를 만들기 전에")
    void overflowingRowsTimesColsIsRejectedImmediately() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                assertInvalid(() -> seatBulkService.bulkCreate(organizerId, sectionId, List.of("A", "B", "C"), 1_431_655_766)));
        assertThat(seatRepository.count()).isZero();
    }

    @Test
    @DisplayName("행 이름은 1~5자, 빈 값·중복 불가")
    void rowLabelsAreValidated() {
        assertInvalid(() -> seatBulkService.bulkCreate(organizerId, sectionId, List.of("ABCDEF"), 2));
        assertInvalid(() -> seatBulkService.bulkCreate(organizerId, sectionId, List.of(" "), 2));
        assertInvalid(() -> seatBulkService.bulkCreate(organizerId, sectionId, List.of("A", "A"), 2));
        assertThat(seatRepository.count()).isZero();
    }

    @Test
    @DisplayName("이미 좌석이 있는 행을 다시 등록하면 500 이 아니라 400")
    void reRegisteringExistingRowIsRejected() {
        assertThat(seatBulkService.bulkCreate(organizerId, sectionId, List.of("A", "B"), 3)).isEqualTo(6);
        assertInvalid(() -> seatBulkService.bulkCreate(organizerId, sectionId, List.of("B", "C"), 3));
        assertThat(seatRepository.count()).isEqualTo(6);
        assertThat(sectionRepository.findById(sectionId).orElseThrow().getSeatCount()).isEqualTo(6);
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.code()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }
}
