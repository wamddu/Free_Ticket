package wamddu.backend.event.controller;

import org.junit.jupiter.api.Test;
import wamddu.backend.event.dto.response.WeeklyRankingEventResponse;
import wamddu.backend.event.dto.response.WeeklyRankingResponse;
import wamddu.backend.event.dto.response.WhatsHotResponse;
import wamddu.backend.event.service.EventService;
import wamddu.backend.event.service.EventListCache;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EventControllerHotCacheTest {
    @Test
    void concurrentRequestsForSameKeyCalculateHotOnce() throws Exception {
        var service = mock(EventService.class);
        var expected = new WhatsHotResponse(null, List.of());
        when(service.whatshot(null, 7)).thenAnswer(invocation -> {
            Thread.sleep(100);
            return expected;
        });
        var controller = new EventController(service, mock(EventListCache.class));
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(20)) {
            List<Callable<WhatsHotResponse>> requests = java.util.stream.IntStream.range(0, 20)
                    .<Callable<WhatsHotResponse>>mapToObj(i -> () -> {
                        start.await();
                        return controller.whatshot(null, 7).getData();
                    }).toList();
            var futures = requests.stream().map(workers::submit).toList();
            start.countDown();
            for (var future : futures) assertThat(future.get()).isSameAs(expected);
        }
        verify(service, times(1)).whatshot(null, 7);
        controller.whatshot(null, 5);
        verify(service, times(1)).whatshot(null, 5);
    }

    @Test
    void weeklyRankingIsCalculatedSeparatelyFromHotAndCachedPerKey() {
        var service = mock(EventService.class);
        var weekly = new WeeklyRankingResponse("musical", WeeklyRankingResponse.Period.WEEKLY, List.of(
                new WeeklyRankingEventResponse(1L, 2L, "weekly", LocalDate.of(2026, 9, 1),
                        LocalDate.of(2026, 10, 1), "Seoul", "poster-2", "musical")));
        when(service.weeklyRanking("musical", 5)).thenReturn(weekly);
        var controller = new EventController(service, mock(EventListCache.class));

        assertThat(controller.getWeeklyRanking("musical", 5).getData()).isSameAs(weekly);
        assertThat(controller.getWeeklyRanking("musical", 5).getData()).isSameAs(weekly);

        verify(service, times(1)).weeklyRanking("musical", 5);
        verify(service, never()).whatshot(any(), any());
    }
}
