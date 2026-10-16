package com.ticketing.show.application;

import com.ticketing.common.error.BusinessException;
import com.ticketing.common.error.ErrorCode;
import com.ticketing.show.domain.Seat;
import com.ticketing.show.domain.SeatRepository;
import com.ticketing.show.domain.Section;
import com.ticketing.show.domain.SectionRepository;
import com.ticketing.show.domain.Show;
import com.ticketing.show.domain.ShowRepository;
import com.ticketing.show.domain.ShowSchedule;
import com.ticketing.show.domain.ShowScheduleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 좌석 일괄 등록 서비스.
 *
 * <p>
 *   row 배열(예: ["A", "B", "C"]) × col 정수(예: 20) 입력으로 좌석을 만들어 일괄 저장.
 *   생성 후 Section.seat_count + ShowSchedule.seat_total 을 누적 갱신한다.
 * </p>
 *
 * <p>
 *   재실행 안전성: 동일 (section_id, row_label, col_no) 가 이미 있으면 DB unique 제약으로 에러.
 *   호출자가 멱등을 원하면 본 서비스 호출 전에 SectionRepository.count 확인 권장.
 * </p>
 */
@Service
@Transactional
public class SeatBulkService {

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    private static final int MAX_SEATS_PER_REQUEST = 5_000;
    /** seats.row_label 컬럼 길이 (V003__shows.sql: VARCHAR(5)). */
    private static final int MAX_ROW_LABEL_LENGTH = 5;

    private final ShowRepository showRepository;
    private final ShowScheduleRepository scheduleRepository;
    private final SectionRepository sectionRepository;
    private final SeatRepository seatRepository;

    public SeatBulkService(ShowRepository showRepository,
                           ShowScheduleRepository scheduleRepository,
                           SectionRepository sectionRepository,
                           SeatRepository seatRepository) {
        this.showRepository = showRepository;
        this.scheduleRepository = scheduleRepository;
        this.sectionRepository = sectionRepository;
        this.seatRepository = seatRepository;
    }

    public int bulkCreate(UUID organizerId, UUID sectionId, List<String> rows, int cols) {
        if (rows == null || rows.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "rows 가 비어있습니다.");
        }
        if (cols <= 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "cols 는 양수여야 합니다. 입력=" + cols);
        }
        // long 으로 곱한다. int 곱셈은 넘쳐서(예: 3 × 1,431,655,766 = 2) 상한을 통과한 뒤
        // 수십억 개 객체를 만들다 OutOfMemoryError 로 서버 전체를 멈췄다 (2026-10-13 QA 실측).
        long requested = (long) rows.size() * cols;
        if (requested > MAX_SEATS_PER_REQUEST) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "한 번에 등록 가능한 좌석 수를 초과했습니다. max=" + MAX_SEATS_PER_REQUEST + " 요청=" + requested);
        }
        int total = (int) requested;
        Set<String> distinctRows = new HashSet<>();
        for (String row : rows) {
            if (row == null || row.isBlank() || row.length() > MAX_ROW_LABEL_LENGTH) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST,
                        "행 이름은 1~" + MAX_ROW_LABEL_LENGTH + "자여야 합니다. 입력=" + row);
            }
            if (!distinctRows.add(row)) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "행 이름이 중복되었습니다. 입력=" + row);
            }
        }

        Section section = sectionRepository.findById(sectionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SHOW_NOT_FOUND,
                        "구역을 찾을 수 없습니다. sectionId=" + sectionId));
        ShowSchedule schedule = scheduleRepository.findForUpdate(section.getShowScheduleId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SCHEDULE_NOT_FOUND,
                        "회차를 찾을 수 없습니다."));
        entityManager.refresh(section);
        SectionCommandService.requireSeatMapEditable(schedule);
        Show show = showRepository.findById(schedule.getShowId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SHOW_NOT_FOUND,
                        "공연을 찾을 수 없습니다."));
        if (!show.getOrganizerId().equals(organizerId)) {
            throw new BusinessException(ErrorCode.SHOW_ACCESS_DENIED, "본인 소유 공연이 아닙니다.");
        }

        // 이미 있는 행에 다시 등록하면 DB unique 제약이 500 으로 터진다 — 미리 400 으로 알린다.
        for (Seat existing : seatRepository.findBySectionId(sectionId)) {
            if (distinctRows.contains(existing.getRowLabel())) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST,
                        "이미 좌석이 있는 행입니다. 행=" + existing.getRowLabel());
            }
        }

        List<Seat> seats = new ArrayList<>(total);
        for (String row : rows) {
            for (int c = 1; c <= cols; c++) {
                seats.add(Seat.create(sectionId, row, c));
            }
        }
        seatRepository.saveAll(seats);

        // seat_count / seat_total 갱신 — 통계용.
        section.addSeatCount(total);
        schedule.addSeatCount(total);

        return total;
    }
    /** Remove an entire row before opening; references and sales history are never deleted. */
    public int deleteRow(UUID organizerId, UUID sectionId, String row) {
        if (row == null || row.isBlank() || row.length() > MAX_ROW_LABEL_LENGTH)
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "행 이름은 1~5자여야 합니다.");
        Section section = sectionRepository.findById(sectionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SHOW_NOT_FOUND, "구역을 찾을 수 없습니다."));
        ShowSchedule schedule = scheduleRepository.findForUpdate(section.getShowScheduleId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SCHEDULE_NOT_FOUND, "회차를 찾을 수 없습니다."));
        Show show = showRepository.findById(schedule.getShowId()).orElseThrow();
        if (!show.getOrganizerId().equals(organizerId)) throw new BusinessException(ErrorCode.SHOW_ACCESS_DENIED, "본인 소유 공연이 아닙니다.");
        entityManager.refresh(section);
        SectionCommandService.requireSeatMapEditable(schedule);
        var selected = seatRepository.findBySectionId(sectionId).stream().filter(s -> row.equals(s.getRowLabel())).toList();
        if (selected.stream().anyMatch(s -> !"AVAILABLE".equals(s.getStatus())))
            throw new BusinessException(ErrorCode.INVALID_SHOW_STATE, "점유·판매된 좌석은 삭제할 수 없습니다.");
        seatRepository.deleteAll(selected);
        section.addSeatCount(-selected.size());
        schedule.addSeatCount(-selected.size());
        return selected.size();
    }

}
