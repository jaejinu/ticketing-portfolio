package com.ticketing.pricing.application;

import com.ticketing.outbox.OutboxWriter;
import com.ticketing.pricing.domain.PricingTick;
import com.ticketing.pricing.domain.PricingTickRepository;
import com.ticketing.pricing.streams.DemandSignalReader;
import com.ticketing.show.domain.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PricingTickServiceTest {
    private final PricingTickRepository ticks = mock(PricingTickRepository.class);
    private final SectionRepository sections = mock(SectionRepository.class);
    private final SeatRepository seats = mock(SeatRepository.class);
    private final DemandSignalReader demand = mock(DemandSignalReader.class);
    private final ShowSchedule schedule = mock(ShowSchedule.class);
    private final UUID scheduleId = UUID.randomUUID();
    private final PricingTickService service = new PricingTickService(
            new PricingCalculator(new PricingProperties()), ticks, sections, seats,
            mock(OutboxWriter.class), JsonMapper.builder().findAndAddModules().build(),
            Clock.systemUTC(), demand);

    private Section section(long available, long held, OptionalLong signal) {
        Section section = Section.create(scheduleId, "VIP", SectionGrade.VIP, 100000);
        when(seats.countBySectionIdAndStatus(section.getId(), SeatStatus.AVAILABLE)).thenReturn(available);
        when(seats.countBySectionIdAndStatus(section.getId(), SeatStatus.HELD)).thenReturn(held);
        when(demand.readRecentDemand(scheduleId, section.getId(), Duration.ofSeconds(2))).thenReturn(signal);
        return section;
    }

    private List<PricingTick> run(Section... inputs) {
        when(schedule.getId()).thenReturn(scheduleId);
        when(sections.findByShowScheduleIdOrderByBasePriceDesc(scheduleId)).thenReturn(List.of(inputs));
        assertThat(service.tickSchedule(schedule)).isEqualTo(inputs.length);
        ArgumentCaptor<PricingTick> saved = ArgumentCaptor.forClass(PricingTick.class);
        verify(ticks, times(inputs.length)).save(saved.capture());
        return saved.getAllValues();
    }

    @Test
    void validZeroDemandDoesNotFallBackToHeldSeats() {
        PricingTick tick = run(section(8, 2, OptionalLong.of(0))).getFirst();
        assertThat(tick.getDemandPressure()).isEqualByComparingTo("0");
        assertThat(tick.getCurrentPrice()).isEqualTo(110000);
    }

    @Test
    void unavailableDemandUsesOnlyEachSectionsHeldSeats() {
        List<PricingTick> result = run(section(8, 2, OptionalLong.empty()),
                section(10, 0, OptionalLong.empty()));
        assertThat(result.get(0).getDemandPressure()).isEqualByComparingTo("0.2");
        assertThat(result.get(0).getCurrentPrice()).isEqualTo(116000);
        assertThat(result.get(1).getDemandPressure()).isEqualByComparingTo("0");
        assertThat(result.get(1).getCurrentPrice()).isEqualTo(100000);
    }

    @Test
    void streamDemandOverridesHeldSeatsAndIsCappedAtOne() {
        List<PricingTick> result = run(section(8, 2, OptionalLong.of(5)),
                section(10, 0, OptionalLong.of(20)));
        assertThat(result.get(0).getDemandPressure()).isEqualByComparingTo("0.5");
        assertThat(result.get(0).getCurrentPrice()).isEqualTo(125000);
        assertThat(result.get(1).getDemandPressure()).isEqualByComparingTo("1");
        assertThat(result.get(1).getCurrentPrice()).isEqualTo(130000);
    }
}
