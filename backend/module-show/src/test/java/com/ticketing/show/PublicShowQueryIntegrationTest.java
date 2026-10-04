package com.ticketing.show;

import com.ticketing.auth.domain.User;
import com.ticketing.auth.domain.UserRepository;
import com.ticketing.show.domain.Seat;
import com.ticketing.show.domain.SeatRepository;
import com.ticketing.show.domain.Section;
import com.ticketing.show.domain.SectionGrade;
import com.ticketing.show.domain.SectionRepository;
import com.ticketing.show.domain.Show;
import com.ticketing.show.domain.ShowRepository;
import com.ticketing.show.domain.ShowSchedule;
import com.ticketing.show.domain.ShowScheduleRepository;
import com.ticketing.show.domain.ShowStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 공개 공연 조회 API 통합 테스트.
 *
 * <h2>검증 항목</h2>
 * <ul>
 *   <li>GET /api/v1/shows — PUBLISHED 만 노출 (DRAFT 제외)</li>
 *   <li>GET /api/v1/shows/{id} — PUBLISHED 단건 상세 + 회차 동봉</li>
 *   <li>GET /api/v1/shows/{id} — DRAFT 는 404 (SHOW_NOT_FOUND)</li>
 *   <li>GET /api/v1/shows/{id} — 미존재 UUID 는 404</li>
 *   <li>GET .../schedules/{scheduleId}/sections — 구역 목록 + base_price</li>
 *   <li>GET .../schedules/{scheduleId}/seats — 좌석 스냅샷</li>
 * </ul>
 *
 * <p>
 *   각 테스트는 {@link #cleanAndSeed()} 가 DB 전체를 비우고 새 시드를 만들어 격리한다.
 *   organizer 1명 + PUBLISHED show 1 (schedule 1 + section 1 + seat 4) + DRAFT show 1.
 * </p>
 */
@AutoConfigureMockMvc
class PublicShowQueryIntegrationTest extends ShowIntegrationTestBase {

    @Autowired MockMvc mvc;
    @Autowired UserRepository userRepository;
    @Autowired ShowRepository showRepository;
    @Autowired ShowScheduleRepository scheduleRepository;
    @Autowired SectionRepository sectionRepository;
    @Autowired SeatRepository seatRepository;

    // 시드 데이터 핸들 — 각 @Test 메서드에서 직접 참조.
    private UUID publishedShowId;
    private UUID draftShowId;
    private UUID scheduleId;
    private UUID sectionId;
    private int seatCount;

    @BeforeEach
    void cleanAndSeed() {
        // ---- 1) FK 역순으로 전체 정리 ---------------------------------------
        // seats → sections → schedules → shows → users 순서로 지워야 FK 위반 안 남.
        seatRepository.deleteAllInBatch();
        sectionRepository.deleteAllInBatch();
        scheduleRepository.deleteAllInBatch();
        showRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();

        // ---- 2) organizer 1명 ------------------------------------------------
        // public GET 만 테스트하므로 권한 부여 불필요. roles=['USER'] 기본값으로 충분.
        User organizer = User.newUser("organizer-it@example.com",
                "$2a$04$test.hash.placeholder.value.for.it.only.xxxxxxxxxxxxxxxxxxxxxx",
                "IT Organizer");
        userRepository.save(organizer);

        // ---- 3) PUBLISHED show + DRAFT show ---------------------------------
        Show published = Show.createWithId(UUID.randomUUID(), organizer.getId(),
                "[IT] Published Concert", "IT Hall",
                "통합 테스트 — 노출 대상", null, ShowStatus.PUBLISHED);
        Show draft     = Show.createWithId(UUID.randomUUID(), organizer.getId(),
                "[IT] Draft Concert",     "IT Hall",
                "통합 테스트 — 비공개",     null, ShowStatus.DRAFT);
        showRepository.save(published);
        showRepository.save(draft);
        this.publishedShowId = published.getId();
        this.draftShowId     = draft.getId();

        // ---- 4) PUBLISHED show 에 schedule + section + seat 4개 --------------
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        ShowSchedule schedule = ShowSchedule.create(
                published.getId(),
                now.plusDays(7),     // starts_at
                now.plusDays(7).plusHours(2), // ends_at
                now.plusDays(3));    // sales_start_at
        scheduleRepository.save(schedule);
        this.scheduleId = schedule.getId();

        Section section = Section.create(schedule.getId(), "VIP", SectionGrade.VIP, 200_000L);
        sectionRepository.save(section);
        this.sectionId = section.getId();

        // 좌석은 적은 수(4개)로 시드 — IT 부담 최소화.
        List<Seat> seats = List.of(
                Seat.create(section.getId(), "A", 1),
                Seat.create(section.getId(), "A", 2),
                Seat.create(section.getId(), "A", 3),
                Seat.create(section.getId(), "A", 4));
        seatRepository.saveAll(seats);
        this.seatCount = seats.size();
    }

    // -----------------------------------------------------------------------------
    // 목록
    // -----------------------------------------------------------------------------

    @Test
    void listShows_returnsOnlyPublished() throws Exception {
        mvc.perform(get("/api/v1/shows"))
                .andExpect(status().isOk())
                // PublicShowController.list 가 {"shows": [...]} 로 감싼다.
                .andExpect(jsonPath("$.shows").isArray())
                .andExpect(jsonPath("$.shows.length()").value(1))
                .andExpect(jsonPath("$.shows[0].id").value(publishedShowId.toString()))
                .andExpect(jsonPath("$.shows[0].status").value("PUBLISHED"))
                // PUBLISHED 가 아니라면 노출되지 않음 — DRAFT 의 id 가 결과에 없어야 함.
                .andExpect(jsonPath("$.shows[?(@.id == '" + draftShowId + "')]").isEmpty());
    }

    // -----------------------------------------------------------------------------
    // 단건 상세
    // -----------------------------------------------------------------------------

    @Test
    void getShow_published_returnsDetailWithSchedules() throws Exception {
        mvc.perform(get("/api/v1/shows/" + publishedShowId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(publishedShowId.toString()))
                .andExpect(jsonPath("$.title").value("[IT] Published Concert"))
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.schedules.length()").value(1))
                .andExpect(jsonPath("$.schedules[0].id").value(scheduleId.toString()));
    }

    @Test
    void getShow_draft_returns404() throws Exception {
        // DRAFT show 는 공개 API 에서 존재 자체를 숨긴다 → SHOW_NOT_FOUND / 404.
        mvc.perform(get("/api/v1/shows/" + draftShowId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SHOW_NOT_FOUND"));
    }

    @Test
    void getShow_unknownId_returns404() throws Exception {
        UUID random = UUID.randomUUID();
        mvc.perform(get("/api/v1/shows/" + random))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SHOW_NOT_FOUND"));
    }

    // -----------------------------------------------------------------------------
    // 구역 / 좌석
    // -----------------------------------------------------------------------------

    @Test
    void listSections_returnsBasePrice() throws Exception {
        mvc.perform(get("/api/v1/shows/" + publishedShowId
                + "/schedules/" + scheduleId + "/sections"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections.length()").value(1))
                .andExpect(jsonPath("$.sections[0].id").value(sectionId.toString()))
                .andExpect(jsonPath("$.sections[0].name").value("VIP"))
                .andExpect(jsonPath("$.sections[0].grade").value("VIP"))
                .andExpect(jsonPath("$.sections[0].basePrice").value(200_000));
    }

    @Test
    void snapshotSeats_returnsAllSeedSeats() throws Exception {
        mvc.perform(get("/api/v1/shows/" + publishedShowId
                + "/schedules/" + scheduleId + "/seats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seats.length()").value(seatCount))
                .andExpect(jsonPath("$.seats[0].status").value("AVAILABLE"));
    }
}
