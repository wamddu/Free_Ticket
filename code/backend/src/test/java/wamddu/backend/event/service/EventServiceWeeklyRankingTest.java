package wamddu.backend.event.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import wamddu.backend.event.dto.response.WeeklyRankingEventResponse;
import wamddu.backend.event.dto.response.WeeklyRankingResponse;
import wamddu.backend.event.dto.response.WhatsHotEventResponse;
import wamddu.backend.event.repository.EventRepository;
import wamddu.backend.global.exception.ApiException;
import wamddu.backend.ticket.repository.TicketRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class EventServiceWeeklyRankingTest {
    static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 12, 12, 0);

    @Mock EventRepository eventRepository;
    @Mock TicketRepository ticketRepository;
    @InjectMocks EventService eventService;

    WeeklyRankingEventResponse weekly(long id) {
        return new WeeklyRankingEventResponse(1L, id, "weekly", LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 12, 1), "Seoul", "poster", "musical");
    }

    @Test
    void usesTheLastSevenDaysOfSales() {
        given(eventRepository.weeklyRanking(null, NOW.minusDays(7), NOW, 5)).willReturn(List.of(weekly(3L)));

        WeeklyRankingResponse response = eventService.weeklyRanking(null, 5, NOW);

        assertThat(response.period()).isEqualTo(WeeklyRankingResponse.Period.WEEKLY);
        assertThat(response.events()).extracting("id").containsExactly(3L);
        verify(eventRepository, never()).whatshot(null, 5);
    }

    @Test
    void fallsBackToAllTimeSalesAndSaysSoWhenNothingSoldThisWeek() {
        given(eventRepository.weeklyRanking(null, NOW.minusDays(7), NOW, 5)).willReturn(List.of());
        given(eventRepository.whatshot(null, 5)).willReturn(List.of(new WhatsHotEventResponse(1L, 9L, "hot",
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 1), "Seoul", "banner", "poster", "musical")));

        WeeklyRankingResponse response = eventService.weeklyRanking(null, 5, NOW);

        assertThat(response.period()).isEqualTo(WeeklyRankingResponse.Period.ALL_TIME);
        assertThat(response.events()).extracting("id").containsExactly(9L);
        assertThat(response.events()).extracting("mainImageUrl").containsExactly("poster");
    }

    @Test
    void validatesLimitAndCategoryLikeWhatsHot() {
        assertThatThrownBy(() -> eventService.weeklyRanking(null, 0, NOW))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("INVALID_LIMIT");
        given(eventRepository.findAllCategories()).willReturn(List.of("musical"));
        assertThatThrownBy(() -> eventService.weeklyRanking("unknown", 5, NOW))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("INVALID_EVENT_CATEGORY");
    }
}
