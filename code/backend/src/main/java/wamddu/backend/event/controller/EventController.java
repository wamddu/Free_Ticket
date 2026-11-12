package wamddu.backend.event.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import java.time.LocalDate;
import wamddu.backend.event.dto.request.EventSearchCriteria;
import wamddu.backend.event.dto.response.EventDetailResponse;
import wamddu.backend.event.dto.response.EventPageResponse;
import wamddu.backend.event.dto.response.WeeklyRankingResponse;
import wamddu.backend.event.dto.response.WhatsHotResponse;
import wamddu.backend.event.service.EventService;
import wamddu.backend.event.service.EventListCache;
import wamddu.backend.global.response.ApiResponse;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.Set;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;
    private final EventListCache eventListCache;
    private static final Set<String> CACHE_CATEGORIES = Set.of("concert", "musical", "play", "classic", "exhibition", "busking");
    private static final long HOT_CACHE_TTL_NANOS = TimeUnit.SECONDS.toNanos(5);
    // Weekly ranking scans orders by paid_at, which has no index yet.
    private static final long WEEKLY_CACHE_TTL_NANOS = TimeUnit.SECONDS.toNanos(60);
    // 서버(인스턴스)마다 따로 두는 캐시다. 여러 서버가 같은 시점에 갱신해야 하면 공유 캐시로 옮긴다.
    private final ConcurrentHashMap<RankingKey, CacheEntry<WhatsHotResponse>> hotCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<RankingKey, CacheEntry<WeeklyRankingResponse>> weeklyCache = new ConcurrentHashMap<>();

    private record RankingKey(String category, Integer limit) {}
    private record CacheEntry<T>(T value, long loadedAtNanos) {
        boolean fresh(long ttlNanos) {
            return System.nanoTime() - loadedAtNanos < ttlNanos;
        }
    }

    /** Loads once per key and TTL; concurrent misses for the same key wait for one load. */
    private static <T> T cached(ConcurrentHashMap<RankingKey, CacheEntry<T>> cache, RankingKey key,
                                long ttlNanos, Supplier<T> loader) {
        var entry = cache.get(key);
        if (entry == null || !entry.fresh(ttlNanos)) {
            entry = cache.compute(key, (ignored, previous) -> previous != null && previous.fresh(ttlNanos)
                    ? previous : new CacheEntry<>(loader.get(), System.nanoTime()));
        }
        return entry.value();
    }

    @GetMapping
    public ResponseEntity<?> getEvents(
            @RequestParam(name = "category", required = false) String category,
            @RequestParam(name = "title", required = false) String title,
            @RequestParam(name = "region", required = false) String region,
            @RequestParam(name = "dateFrom", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(name = "dateTo", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(name = "maxPrice", required = false) Integer maxPrice,
            @RequestParam(name = "availableOnly", required = false) Boolean availableOnly,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "30") int size,
            @RequestParam(name = "sort", defaultValue = "latest") String sort
    ) {
        EventService.validatePagination(page, size, sort);
        var criteria = new EventSearchCriteria(category, title, region, dateFrom, dateTo, maxPrice, availableOnly);
        if ((category == null || CACHE_CATEGORIES.contains(category)) && title == null && region == null && dateFrom == null && dateTo == null
                && maxPrice == null && availableOnly == null && eventListCache.isEnabled()) {
            String field = (category == null ? "all" : category) + ":" + sort + ":" + page + ":" + size;
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                    .body(eventListCache.getOrLoad(field, () -> loadEvents(criteria, page, size, sort)));
        }
        return ResponseEntity.ok(loadEvents(criteria, page, size, sort));
    }

    private ApiResponse<EventPageResponse> loadEvents(EventSearchCriteria criteria, int page, int size, String sort) {
        EventPageResponse response = eventService.getEventPage(criteria, page, size, sort);
        String message = response.events().isEmpty() ? "조회된 이벤트가 없습니다." : "이벤트 목록 조회에 성공했습니다.";
        return ApiResponse.success(message, response);
    }

    @GetMapping("/whats-hot")
    public ApiResponse<WhatsHotResponse> whatshot(
            @RequestParam(name = "category", required = false) String category,
            @RequestParam(name = "limit", required = false, defaultValue = "5") Integer limit
    ) {
        return ApiResponse.success("WHAT'S HOT 이벤트 조회에 성공했습니다.", cachedHot(category, limit));
    }

    private WhatsHotResponse cachedHot(String category, Integer limit) {
        return cached(hotCache, new RankingKey(category, limit), HOT_CACHE_TTL_NANOS,
                () -> eventService.whatshot(category, limit));
    }

    @GetMapping("/weekly-ranking")
    public ApiResponse<WeeklyRankingResponse> getWeeklyRanking(
            @RequestParam(name = "category", required = false) String category,
            @RequestParam(name = "limit", required = false, defaultValue = "5") Integer limit
    ) {
        return ApiResponse.success("주간 이벤트 순위 조회에 성공했습니다.", cached(weeklyCache,
                new RankingKey(category, limit), WEEKLY_CACHE_TTL_NANOS,
                () -> eventService.weeklyRanking(category, limit)));
    }

    @GetMapping("/{eventId}")
    public ApiResponse<EventDetailResponse> getEventTicketsById(
            @PathVariable(name = "eventId") Long eventId
    ) {
        return ApiResponse.success("이벤트 상세 조회에 성공했습니다.", eventService.getDetail(eventId));
    }
}
