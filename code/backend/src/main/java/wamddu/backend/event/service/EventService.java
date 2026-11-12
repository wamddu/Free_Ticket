package wamddu.backend.event.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import wamddu.backend.event.domain.Event;
import wamddu.backend.event.dto.request.EventSearchCriteria;
import wamddu.backend.event.dto.response.*;
import wamddu.backend.event.repository.EventRepository;
import wamddu.backend.global.exception.ApiException;
import wamddu.backend.ticket.dto.response.EventTicketResponse;
import wamddu.backend.ticket.domain.Ticket;
import wamddu.backend.ticket.repository.TicketRepository;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EventService {
    static final Duration WEEKLY_WINDOW = Duration.ofDays(7);
    private static final Set<String> SEARCH_CATEGORIES = Set.of(
            "concert", "musical", "play", "classic", "exhibition", "busking");

    private final EventRepository eventRepository;
    private final TicketRepository ticketRepository;

    public EventListResponse getEvents(String category, String title, String region) {
        return getEvents(new EventSearchCriteria(category, title, region, null, null, null, null));
    }

    public EventListResponse getEvents(EventSearchCriteria criteria) {
        return new EventListResponse(searchEvents(criteria, Pageable.unpaged(Sort.by(Sort.Direction.DESC, "id"))).getContent());
    }

    public EventPageResponse getEventPage(EventSearchCriteria criteria, int page, int size, String sort) {
        validatePagination(page, size, sort);
        Sort order = Sort.by(Sort.Direction.DESC, "id");
        if ("closingSoon".equals(sort)) {
            order = Sort.by(Sort.Direction.ASC, "endDate").and(order);
        }
        Slice<EventSummaryResponse> events = searchEvents(criteria, PageRequest.of(page, size, order));
        return new EventPageResponse(events.getContent(), page, size, events.hasNext());
    }

    public static void validatePagination(int page, int size, String sort) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE
                || !("latest".equals(sort) || "closingSoon".equals(sort))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGINATION", "유효한 페이지, 크기(1~100), 정렬 조건을 입력해 주세요.");
        }
    }

    private Slice<EventSummaryResponse> searchEvents(EventSearchCriteria criteria, Pageable pageable) {
        String category = normalizeSearchValue(criteria.category());
        String title = normalizeSearchValue(criteria.title());
        String region = normalizeSearchValue(criteria.region());
        if (category != null && !SEARCH_CATEGORIES.contains(category)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_EVENT_CATEGORY", "유효하지 않은 이벤트 카테고리입니다.");
        }

        // 지역은 주소 문자열 부분 일치로 찾는다. 약칭이나 정확한 행정구역 구분이 필요해지면 지역 코드를 도입한다.
        validateTicketFilters(criteria);
        if (criteria.hasTicketFilters()) {
            return eventRepository.searchEventsWithTickets(
                    category, containsPattern(title), containsPattern(region),
                    criteria.dateFrom() == null ? null : criteria.dateFrom().atStartOfDay(),
                    criteria.dateTo() == null ? null : criteria.dateTo().plusDays(1).atStartOfDay(),
                    criteria.maxPrice(), Boolean.TRUE.equals(criteria.availableOnly()), LocalDateTime.now(), pageable);
        }
        return eventRepository.searchEvents(category, containsPattern(title), containsPattern(region), pageable);
    }

    private void validateTicketFilters(EventSearchCriteria criteria) {
        if (criteria.maxPrice() != null && criteria.maxPrice() < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MAX_PRICE", "최대 가격은 0원 이상이어야 합니다.");
        }
        // Keep dates within the database calendar range and allow an exclusive next-day upper bound.
        LocalDate minDate = LocalDate.of(1000, 1, 1);
        LocalDate maxDate = LocalDate.of(9999, 12, 30);
        if ((criteria.dateFrom() != null && (criteria.dateFrom().isBefore(minDate) || criteria.dateFrom().isAfter(maxDate)))
                || (criteria.dateTo() != null && (criteria.dateTo().isBefore(minDate) || criteria.dateTo().isAfter(maxDate)))
                || (criteria.dateFrom() != null && criteria.dateTo() != null && criteria.dateFrom().isAfter(criteria.dateTo()))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE", "유효한 날짜 범위를 입력해 주세요. 시작일은 종료일보다 늦을 수 없습니다.");
        }
    }

    private String normalizeSearchValue(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.strip();
        if (trimmed.length() > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SEARCH_VALUE", "검색 조건은 100자 이하로 입력해 주세요.");
        }
        return trimmed;
    }

    private String containsPattern(String value) {
        if (value == null) return null;
        return "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    public WeeklyRankingResponse weeklyRanking(String category, Integer limit) {
        return weeklyRanking(category, limit, LocalDateTime.now());
    }

    WeeklyRankingResponse weeklyRanking(String category, Integer limit, LocalDateTime now) {
        validateRankingQuery(category, limit);
        List<WeeklyRankingEventResponse> weekly =
                eventRepository.weeklyRanking(category, now.minus(WEEKLY_WINDOW), now, limit);
        if (!weekly.isEmpty()) {
            return new WeeklyRankingResponse(category, WeeklyRankingResponse.Period.WEEKLY, weekly);
        }
        // Nothing sold in the window: show cumulative sales instead of an empty section, and say so.
        List<WeeklyRankingEventResponse> allTime = eventRepository.whatshot(category, limit).stream()
                .map(event -> new WeeklyRankingEventResponse(event.getRank(), event.getId(), event.getName(),
                        event.getStartDate(), event.getEndDate(), event.getLocation(),
                        event.getMainImageUrl(), event.getCategory()))
                .toList();
        return new WeeklyRankingResponse(category, WeeklyRankingResponse.Period.ALL_TIME, allTime);
    }

    private void validateRankingQuery(String category, Integer limit) {
        if (limit == null || limit < 1 || limit > 20) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_LIMIT", "limit은 1 이상 20 이하로 입력해 주세요.");
        }
        if (category != null && !eventRepository.findAllCategories().contains(category)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_EVENT_CATEGORY", "유효하지 않은 이벤트 카테고리입니다.");
        }
    }

    public WhatsHotResponse whatshot(String category, Integer limit) {
        validateRankingQuery(category, limit);

        List<WhatsHotEventResponse> allEvents = eventRepository.whatshot(category, limit);

        return new WhatsHotResponse(category, allEvents);
    }

    public EventDetailResponse getDetail(Long id) {
        Event event = eventRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND", "이벤트를 찾을 수 없습니다."));

        List<Ticket> allTickets = ticketRepository.getAllEventTickets(id);

        List<EventTicketResponse> responseTickets = allTickets.stream()
                .map(EventTicketResponse::from)
                .toList();

        return EventDetailResponse.of(EventInfoResponse.from(event), responseTickets);
    }
}
