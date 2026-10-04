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
import java.util.List;
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

    private static final int MAX_SEATS_PER_REQUEST = 5_000;

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
        int total = rows.size() * cols;
        if (total > MAX_SEATS_PER_REQUEST) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "한 번에 등록 가능한 좌석 수를 초과했습니다. max=" + MAX_SEATS_PER_REQUEST + " 요청=" + total);
        }

        Section section = sectionRepository.findById(sectionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SHOW_NOT_FOUND,
                        "구역을 찾을 수 없습니다. sectionId=" + sectionId));
        ShowSchedule schedule = scheduleRepository.findById(section.getShowScheduleId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SCHEDULE_NOT_FOUND,
                        "회차를 찾을 수 없습니다."));
        Show show = showRepository.findById(schedule.getShowId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SHOW_NOT_FOUND,
                        "공연을 찾을 수 없습니다."));
        if (!show.getOrganizerId().equals(organizerId)) {
            throw new BusinessException(ErrorCode.SHOW_ACCESS_DENIED, "본인 소유 공연이 아닙니다.");
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
}
