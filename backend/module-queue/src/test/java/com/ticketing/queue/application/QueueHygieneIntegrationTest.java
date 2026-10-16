package com.ticketing.queue.application;

import com.ticketing.auth.domain.User;
import com.ticketing.auth.domain.UserRepository;
import com.ticketing.common.error.BusinessException;
import com.ticketing.common.error.ErrorCode;
import com.ticketing.queue.QueueIntegrationTestApp;
import com.ticketing.queue.QueueIntegrationTestBase;
import com.ticketing.show.domain.Show;
import com.ticketing.show.domain.ShowRepository;
import com.ticketing.show.domain.ShowSchedule;
import com.ticketing.show.domain.ShowScheduleRepository;
import com.ticketing.show.domain.ShowStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 대기열 위생 — 2026-10-13 QA P2 에서 실측된 문제를 고정한다.
 * <ul>
 *   <li>없는 회차·판매 전 회차에도 줄을 설 수 있었다 (판매 전이면 영원히 대기)</li>
 *   <li>같은 사람이 부를 때마다 새 표를 받아 줄 여러 자리를 차지했다</li>
 *   <li>남의 표 번호로 위치·입장 여부를 볼 수 있었다</li>
 *   <li>만료된 표가 줄에 남아 위치를 부풀리고, "anon" 으로 입장 자리를 낭비했다</li>
 * </ul>
 */
@SpringBootTest(classes = QueueIntegrationTestApp.class)
class QueueHygieneIntegrationTest extends QueueIntegrationTestBase {

    @Autowired VirtualQueueService queueService;
    @Autowired QueueEntryPolicy entryPolicy;
    @Autowired ShowRepository showRepository;
    @Autowired ShowScheduleRepository scheduleRepository;
    @Autowired RedissonClient redisson;
    @Autowired UserRepository userRepository;

    /** 같은 컨테이너를 쓰는 다른 테스트(예: "판매 중 회차 없으면 빈 목록")에 회차가 새지 않게 지운다. */
    private final List<UUID> createdUsers = new ArrayList<>();

    @AfterEach
    void removeCreatedShows() {
        for (UUID organizerId : createdUsers) {
            for (Show show : showRepository.findByOrganizerIdOrderByCreatedAtDesc(organizerId)) {
                scheduleRepository.deleteAll(scheduleRepository.findByShowIdOrderByStartsAtAsc(show.getId()));
                showRepository.delete(show);
            }
            userRepository.deleteById(organizerId);
        }
        createdUsers.clear();
    }

    @Test
    @DisplayName("공개된 공연의 판매 중 회차에만 줄을 설 수 있다")
    void onlyOnSaleSchedulesAcceptEntries() {
        assertCode(() -> entryPolicy.requireOpen(UUID.randomUUID()), ErrorCode.SCHEDULE_NOT_FOUND);

        ShowSchedule scheduled = schedule(ShowStatus.PUBLISHED, false);
        assertCode(() -> entryPolicy.requireOpen(scheduled.getId()), ErrorCode.SALES_NOT_OPEN);

        ShowSchedule draftShow = schedule(ShowStatus.DRAFT, true);
        assertCode(() -> entryPolicy.requireOpen(draftShow.getId()), ErrorCode.SALES_NOT_OPEN);

        entryPolicy.requireOpen(schedule(ShowStatus.PUBLISHED, true).getId());
    }

    @Test
    @DisplayName("같은 사람은 표 하나 — 다시 줄을 서도 같은 표와 같은 자리")
    void oneTicketPerPerson() {
        UUID schedule = UUID.randomUUID();
        String user = UUID.randomUUID().toString();
        QueueStatusSnapshot first = queueService.enqueue(schedule, user);
        queueService.enqueue(schedule, UUID.randomUUID().toString());
        QueueStatusSnapshot again = queueService.enqueue(schedule, user);

        assertThat(again.ticket()).isEqualTo(first.ticket());
        assertThat(again.position()).isEqualTo(first.position());
        assertThat(queueService.waitingSize(schedule)).isEqualTo(2);

        // 입장한 뒤 다시 불러도 입장 상태를 잃지 않는다.
        queueService.admitHead(schedule, 1);
        assertThat(queueService.enqueue(schedule, user).state()).isEqualTo(QueueState.ADMITTED);
    }

    @Test
    @DisplayName("남의 표는 조회할 수 없다")
    void ticketStatusIsOwnerOnly() {
        UUID schedule = UUID.randomUUID();
        String owner = UUID.randomUUID().toString();
        UUID ticket = queueService.enqueue(schedule, owner).ticket();

        assertThat(queueService.status(ticket, owner).state()).isEqualTo(QueueState.WAITING);
        assertCode(() -> queueService.status(ticket, UUID.randomUUID().toString()), ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("만료된 표는 줄에서 빠지고 입장 자리를 차지하지 않는다")
    void zombieTicketsAreDropped() {
        UUID schedule = UUID.randomUUID();
        // 1) 주인 정보가 사라진 표 — 예전엔 "anon" 으로 입장했다.
        UUID orphan = queueService.enqueue(schedule, UUID.randomUUID().toString()).ticket();
        redisson.getBucket(VirtualQueueService.HOLDER_PREFIX + orphan).delete();
        // 2) ticket-ttl(1시간)보다 오래 기다린 표 — 위치 계산에서 빠져야 한다.
        redisson.getScoredSortedSet(VirtualQueueService.WAITING_PREFIX + schedule)
                .add(System.currentTimeMillis() - 2 * 3600_000L, UUID.randomUUID().toString());
        String live = UUID.randomUUID().toString();
        queueService.enqueue(schedule, live);

        assertThat(queueService.admitHead(schedule, 1)).isZero(); // 오래된 표는 지워지고, 고아 표는 건너뜀
        assertThat(queueService.waitingSize(schedule)).isEqualTo(1);
        assertThat(queueService.admitHead(schedule, 1)).isEqualTo(1);
        assertThat(queueService.isAdmitted(schedule, live)).isTrue();
        // 줄 자체에도 만료가 걸려 있다.
        queueService.enqueue(schedule, UUID.randomUUID().toString());
        assertThat(redisson.getScoredSortedSet(VirtualQueueService.WAITING_PREFIX + schedule).remainTimeToLive())
                .isPositive();
    }

    private ShowSchedule schedule(ShowStatus showStatus, boolean onSale) {
        User organizer = userRepository.save(User.newUser("queue-hygiene-" + UUID.randomUUID() + "@example.com",
                "$2a$04$test.hash.placeholder.value.for.it.only.xxxxxxxxxxxxxxxxxxxxxx", "주최"));
        createdUsers.add(organizer.getId());
        Show show = showRepository.save(Show.createWithId(UUID.randomUUID(), organizer.getId(),
                "대기열 위생", "홀", null, null, showStatus));
        OffsetDateTime startsAt = OffsetDateTime.now(ZoneOffset.UTC).plusDays(10);
        ShowSchedule schedule = ShowSchedule.create(show.getId(), startsAt, null, startsAt.minusDays(5));
        if (onSale) schedule.markOnSale();
        return scheduleRepository.save(schedule);
    }

    private static void assertCode(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).code())
                .isEqualTo(code);
    }
}
