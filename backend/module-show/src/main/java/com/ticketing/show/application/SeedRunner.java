package com.ticketing.show.application;

import com.ticketing.auth.domain.User;
import com.ticketing.auth.domain.UserRepository;
import com.ticketing.show.domain.Seat;
import com.ticketing.show.domain.Section;
import com.ticketing.show.domain.SectionGrade;
import com.ticketing.show.domain.SectionRepository;
import com.ticketing.show.domain.SeatRepository;
import com.ticketing.show.domain.Show;
import com.ticketing.show.domain.ShowRepository;
import com.ticketing.show.domain.ShowSchedule;
import com.ticketing.show.domain.ShowScheduleRepository;
import com.ticketing.show.domain.ShowStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 데모 데이터 시드 실행기.
 *
 * <p>
 *   {@code app.show.seed.enabled=true} 일 때만 부팅 직후 데모 데이터를 생성한다.
 *   Phase 3 (module-seat) 가 곧바로 redis 스냅샷을 만들 수 있도록 좌석 400 개를 확보하는 게 목적.
 * </p>
 *
 * <h2>idempotent 처리</h2>
 * <pre>
 *   organizer email "organizer@demo.ticketing.local" 존재?
 *     ├─ YES ─▶ 시드 스킵 (이미 실행됨)
 *     └─ NO ──▶ 데모 카탈로그 전체 시드 → COMMIT
 * </pre>
 *
 * <h2>데모 카탈로그 (2026-07-29 확장)</h2>
 * <p>
 *   UI 데모가 "실제 서비스처럼" 보이도록 장르가 다른 공연 7개를 시드한다.
 *   포스터는 frontend/public/posters/*.svg (백엔드는 경로 문자열만 보관).
 *   회차는 공연마다 1~3개 — {@code start-on-sale=true} 면 각 공연의 첫 회차만
 *   즉시 ON_SALE, 나머지는 SCHEDULED 로 남겨 "오픈 임박" 상태도 함께 시연한다.
 *   좌석은 회차당 4구역 × 100석 = 400석.
 * </p>
 *
 * <h2>데모 계정</h2>
 * <ul>
 *   <li>organizer@demo.ticketing.local / Demo1234! — ORGANIZER (주최자 콘솔)</li>
 *   <li>admin@demo.ticketing.local     / Demo1234! — ADMIN (운영자 콘솔 /admin)</li>
 * </ul>
 *
 * <h2>왜 ApplicationReadyEvent?</h2>
 * 컨텍스트 완전 초기화 이후 트랜잭션 프록시가 동작해야 @Transactional 이 적용된다.
 * @PostConstruct 시점에는 self-invocation 으로 인해 @Transactional 이 적용되지 않을 위험이 있음.
 * 또한 ApplicationReadyEvent 는 부팅 완료 시점이라 모든 빈/Flyway 마이그레이션 이후라 안전.
 *
 * <h2>비밀번호 해시</h2>
 * BCrypt(strength=12) 로 미리 계산한 'Demo1234!' 의 해시는 application-local.yml 에서 주입.
 * (테스트 프로파일에선 자동으로 enabled=false 라 시드가 돌지 않음.)
 */
@Component
public class SeedRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedRunner.class);

    /** 시드 organizer 의 이메일. 시드 멱등성의 키 역할도 한다. */
    private static final String DEMO_ORGANIZER_EMAIL = "organizer@demo.ticketing.local";
    private static final String DEMO_ORGANIZER_NAME = "데모 주최자";
    /** 운영자 콘솔(/admin) 시연용 ADMIN 계정 — 비밀번호는 organizer 와 동일한 데모 해시. */
    private static final String DEMO_ADMIN_EMAIL = "admin@demo.ticketing.local";
    private static final String DEMO_ADMIN_NAME = "데모 운영자";

    /**
     * 데모 공연 정의 — 제목/장소/설명/포스터/가격 스케일/회차 구성.
     *
     * @param priceScale        VIP 기준가 대비 배율. 공연마다 가격대가 달라야 목록이 실감난다.
     * @param scheduleDays      회차별 "며칠 뒤 시작" 오프셋 — 원소 수 = 회차 수.
     * @param openFirstSchedule start-on-sale=true 일 때 첫 회차를 ON_SALE 로 열지 여부.
     *                          false 인 공연은 "오픈 임박(SCHEDULED)" 상태 시연용.
     */
    private record DemoShow(String title, String venue, String description,
                            String posterUrl, double priceScale, int[] scheduleDays,
                            boolean openFirstSchedule) {
    }

    /** 장르가 겹치지 않게 구성한 7개 공연. 포스터는 frontend/public/posters/ 와 1:1. */
    private static final List<DemoShow> CATALOG = List.of(
            new DemoShow("AURORA — 밤을 켜는 목소리", "올림픽 체조경기장 (서울)",
                    "다이나믹 프라이싱이 실시간으로 움직이는 대표 데모 공연. 수요가 몰리면 가격이 오르고, 한산하면 내려갑니다.",
                    "/posters/aurora.svg", 1.0, new int[]{7, 8}, true),
            new DemoShow("NEON CITY 페스타", "잠실 실내체육관 (서울)",
                    "힙합 × EDM 크로스오버 라인업. 오픈런 트래픽 제어(대기열·봇 방어) 시연에 적합한 고수요 공연.",
                    "/posters/neon.svg", 0.9, new int[]{10, 11}, true),
            new DemoShow("NOCTURNE — 겨울밤의 협주곡", "예술의전당 콘서트홀 (서울)",
                    "피아노 협주곡 중심의 클래식 나이트. 완만한 수요 곡선 — 가격 하한(floor) 동작을 관찰하기 좋습니다.",
                    "/posters/classic.svg", 1.2, new int[]{14, 21}, true),
            new DemoShow("뮤지컬 〈붉은 커튼〉", "블루스퀘어 신한카드홀 (서울)",
                    "3주 연속 공연 — 회차가 많아 회차별 수요 편차(주말 vs 평일)를 비교해볼 수 있습니다.",
                    "/posters/musical.svg", 1.1, new int[]{5, 12, 19}, true),
            new DemoShow("MIDNIGHT SWING 재즈 나이트", "세종문화회관 M씨어터 (서울)",
                    "미드나잇 스윙 밴드의 단독 공연. 소극장 규모의 잔잔한 수요 패턴.",
                    "/posters/jazz.svg", 0.6, new int[]{9}, true),
            new DemoShow("PLANET US 팬미팅", "KSPO DOME (서울)",
                    "판매 오픈 전(SCHEDULED) 상태 데모 — 오픈 임박 화면과 organizer 의 판매 오픈 토글을 시연합니다.",
                    "/posters/fanmeet.svg", 1.4, new int[]{16, 17}, false),
            new DemoShow("RIOT WAVE 록 페스티벌", "난지 한강공원 (서울)",
                    "야외 록 페스티벌. 스탠딩 구역 중심의 가격 구성.",
                    "/posters/rock.svg", 0.8, new int[]{25}, true));

    private final UserRepository userRepository;
    private final ShowRepository showRepository;
    private final ShowScheduleRepository scheduleRepository;
    private final SectionRepository sectionRepository;
    private final SeatRepository seatRepository;

    private final boolean enabled;
    private final boolean startOnSale;
    private final String organizerPasswordHash;

    public SeedRunner(UserRepository userRepository,
                      ShowRepository showRepository,
                      ShowScheduleRepository scheduleRepository,
                      SectionRepository sectionRepository,
                      SeatRepository seatRepository,
                      @Value("${app.show.seed.enabled:false}") boolean enabled,
                      @Value("${app.show.seed.start-on-sale:false}") boolean startOnSale,
                      @Value("${app.show.seed.organizer-password-hash:}") String organizerPasswordHash) {
        this.userRepository = userRepository;
        this.showRepository = showRepository;
        this.scheduleRepository = scheduleRepository;
        this.sectionRepository = sectionRepository;
        this.seatRepository = seatRepository;
        this.enabled = enabled;
        this.startOnSale = startOnSale;
        this.organizerPasswordHash = organizerPasswordHash;
    }

    /**
     * 부팅 완료 후 시드 실행. @Transactional 이 효과를 보려면 외부에서 본 메서드가 호출되어야 하므로
     * 이벤트 리스너 진입점 자체에 트랜잭션을 두었다 (Spring 이 AOP 프록시로 가로챈다).
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void runIfEnabled() {
        if (!enabled) {
            log.debug("[seed] disabled (app.show.seed.enabled=false). 스킵.");
            return;
        }
        if (organizerPasswordHash == null || organizerPasswordHash.isBlank()) {
            log.warn("[seed] organizer-password-hash 가 비어있어 시드를 진행할 수 없습니다.");
            return;
        }
        try {
            seed();
        } catch (Exception ex) {
            // 시드 실패는 부팅 자체를 막지 않는다 (DEV 편의). 다만 로그로 명확히 노출.
            log.error("[seed] 데모 데이터 시드 실패", ex);
        }
    }

    private void seed() {
        // ---- 1) idempotency : 이미 organizer 가 존재하면 종료 ------------------
        Optional<User> existing = userRepository.findByEmail(DEMO_ORGANIZER_EMAIL);
        if (existing.isPresent()) {
            log.info("[seed] 데모 organizer 가 이미 존재합니다. 시드 스킵.");
            return;
        }

        // ---- 2) 데모 계정 생성 (organizer + admin) ----------------------------
        User organizer = User.newUser(DEMO_ORGANIZER_EMAIL, organizerPasswordHash, DEMO_ORGANIZER_NAME);
        // User 팩토리가 기본 roles=['USER'] 로 고정해버려서 역할 추가를 reflection 으로 처리.
        // (module-auth 를 건드릴 수 없는 본 phase 제약 회피. Phase 8 에서 도메인 메서드 추가 예정.)
        setRolesViaReflection(organizer, new String[]{"USER", "ORGANIZER"});
        userRepository.save(organizer);
        log.info("[seed] organizer 생성: {} ({})", organizer.getEmail(), organizer.getId());

        // 운영자 콘솔(/admin) 시연용 — 데모 비밀번호는 organizer 와 동일 (Demo1234!).
        User admin = User.newUser(DEMO_ADMIN_EMAIL, organizerPasswordHash, DEMO_ADMIN_NAME);
        setRolesViaReflection(admin, new String[]{"USER", "ADMIN"});
        userRepository.save(admin);
        log.info("[seed] admin 생성: {} ({})", admin.getEmail(), admin.getId());

        // ---- 3) 카탈로그 순회 — show + 회차 + 구역 + 좌석 ---------------------
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        int showCount = 0;
        int scheduleCount = 0;
        int seatCount = 0;
        for (DemoShow demo : CATALOG) {
            Show show = Show.createWithId(
                    UUID.randomUUID(), organizer.getId(),
                    demo.title(), demo.venue(), demo.description(), demo.posterUrl(),
                    ShowStatus.PUBLISHED);
            showRepository.save(show);
            showCount++;

            for (int i = 0; i < demo.scheduleDays().length; i++) {
                int daysAhead = demo.scheduleDays()[i];
                // KST(+09) 19:00 = UTC 10:00. 판매 시작은 공연 3일 전 KST 14:00 (= UTC 05:00).
                OffsetDateTime startsAt = now.plusDays(daysAhead)
                        .withHour(10).withMinute(0).withSecond(0).withNano(0);
                OffsetDateTime endsAt = startsAt.plusHours(2);
                OffsetDateTime salesAt = startsAt.minusDays(3).withHour(5);

                ShowSchedule schedule = ShowSchedule.create(show.getId(), startsAt, endsAt, salesAt);
                // 시연 옵션 — start-on-sale=true 면 각 공연의 "첫 회차만" 즉시 ON_SALE.
                // 나머지 회차는 SCHEDULED 로 남겨 오픈 임박/판매 중이 목록에 섞여 보이게 한다.
                // pricing / queue 스케줄러는 ON_SALE 회차만 보므로 부하도 회차 수에 비례해 억제됨.
                // 운영에선 절대 사용 금지 (organizer 명시 open 호출이 정상 경로).
                if (startOnSale && i == 0 && demo.openFirstSchedule()) {
                    schedule.markOnSale();
                }
                // ID 직접 할당 엔티티는 save() 가 merge 경로 — 이후의 addSeatCount 는
                // 반드시 save() 가 돌려준 "관리 인스턴스" 에 해야 DB 에 반영된다.
                // (원본에 하면 조용히 유실 → 모든 회차 seat_total=0 이던 버그의 원인)
                ShowSchedule managed = scheduleRepository.save(schedule);
                scheduleCount++;

                seatCount += seedSectionsAndSeats(managed, demo.priceScale());
            }
        }
        log.info("[seed] 데모 카탈로그 시드 완료 — show {}개 / 회차 {}개 / 좌석 {}석", showCount, scheduleCount, seatCount);
    }

    /**
     * 회차 하나에 VIP/R/S/A 구역 + 구역당 100석(row A~E × col 1~20) 생성.
     *
     * @param priceScale 기준가(VIP 200k / R 150k / S 100k / A 70k) 에 곱하는 공연별 배율.
     *                   1000원 단위로 반올림해 "실제 티켓 가격" 처럼 보이게 한다.
     * @return 생성한 좌석 수 (= 400)
     */
    private int seedSectionsAndSeats(ShowSchedule schedule, double priceScale) {
        // saveAll 반환값(관리 인스턴스들)을 써야 아래 addSeatCount 가 DB 에 반영된다 — 위 merge 주석 참고.
        List<Section> sections = sectionRepository.saveAll(List.of(
                Section.create(schedule.getId(), "VIP", SectionGrade.VIP, scaled(200_000L, priceScale)),
                Section.create(schedule.getId(), "R",   SectionGrade.R,   scaled(150_000L, priceScale)),
                Section.create(schedule.getId(), "S",   SectionGrade.S,   scaled(100_000L, priceScale)),
                Section.create(schedule.getId(), "A",   SectionGrade.A,   scaled(70_000L, priceScale))));

        int total = 0;
        for (Section section : sections) {
            List<Seat> seats = new ArrayList<>(100);
            for (char row = 'A'; row <= 'E'; row++) {
                for (int col = 1; col <= 20; col++) {
                    seats.add(Seat.create(section.getId(), String.valueOf(row), col));
                }
            }
            seatRepository.saveAll(seats);
            section.addSeatCount(seats.size());
            total += seats.size();
        }
        schedule.addSeatCount(total);
        return total;
    }

    /** 기준가 × 배율 → 1000원 단위 반올림. */
    private static long scaled(long base, double scale) {
        return Math.round(base * scale / 1000.0) * 1000L;
    }

    /**
     * User 의 roles 필드를 reflection 으로 교체.
     * <p>
     *   module-auth 의 User 는 roles 변경 도메인 메서드가 없어 시드용 reflection 으로 우회한다.
     *   Phase 8 에 User#addRole / removeRole 추가 후 본 메서드를 제거할 것.
     * </p>
     */
    private void setRolesViaReflection(User user, String[] roles) {
        try {
            Field f = User.class.getDeclaredField("roles");
            f.setAccessible(true);
            f.set(user, roles);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("User.roles 필드 reflection 실패", ex);
        }
    }
}
