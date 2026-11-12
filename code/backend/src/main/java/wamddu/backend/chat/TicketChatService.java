package wamddu.backend.chat;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import wamddu.backend.event.dto.request.EventSearchCriteria;
import wamddu.backend.event.dto.response.EventSummaryResponse;
import wamddu.backend.event.service.EventService;
import wamddu.backend.global.exception.ApiException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class TicketChatService {
    private final GeminiSearchClient gemini;
    private final EventService eventService;
    private final ArrayDeque<Long> recentRequests = new ArrayDeque<>();
    private static final Map<String, String> CATEGORY_LABELS = Map.of(
            "concert", "콘서트", "musical", "뮤지컬", "play", "연극",
            "classic", "클래식/무용", "exhibition", "전시/스포츠", "busking", "버스킹");

    public record Reply(String message, List<EventSummaryResponse> events) {}

    public Reply chat(List<String> messages) {
        reserveRequest();
        var intent = gemini.extract(messages);
        if (!Boolean.TRUE.equals(intent.search())) {
            return new Reply("지역·날짜·장르·최대 예산으로 공연을 찾을 수 있어요. 예: 서울에서 이번 주말 5만 원 이하 뮤지컬 찾아줘.\n그 외 조건은 검색을 지원하지 않습니다.", List.of());
        }
        var criteria = new EventSearchCriteria(intent.category(), intent.title(), intent.region(),
                intent.dateFrom(), intent.dateTo(), intent.maxPrice(), intent.availableOnly());
        List<EventSummaryResponse> events;
        try {
            events = eventService.getEvents(criteria).events();
        } catch (ApiException exception) {
            if (exception.getStatus() != HttpStatus.BAD_REQUEST) throw exception;
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "CHAT_INVALID_SEARCH", "검색 조건이 올바르지 않습니다. 날짜와 예산을 다시 확인해 주세요.");
        }
        String eventLabel = Boolean.TRUE.equals(criteria.availableOnly()) ? "예매 가능한 공연" : "공연";
        String result = events.isEmpty() ? "조건에 맞는 " + eventLabel + "이 없습니다. 조건을 바꿔서 다시 찾아보세요."
                : eventLabel + " " + events.size() + "개를 찾았습니다."
                + (events.size() > 5 ? " 먼저 5개를 보여드릴게요. 조건을 좁히면 다른 공연도 찾을 수 있어요." : "");
        return new Reply(summary(criteria) + "\n" + result, events.stream().limit(5).toList());
    }

    private String summary(EventSearchCriteria criteria) {
        var parts = new ArrayList<String>();
        if (criteria.region() != null && !criteria.region().isBlank()) parts.add(criteria.region());
        if (criteria.category() != null && !criteria.category().isBlank()) parts.add(CATEGORY_LABELS.get(criteria.category().strip()));
        if (criteria.title() != null && !criteria.title().isBlank()) parts.add(criteria.title());
        if (criteria.dateFrom() != null) {
            parts.add(criteria.dateFrom() + (criteria.dateTo() == null ? " 이후" : " ~ " + criteria.dateTo()));
        } else if (criteria.dateTo() != null) {
            parts.add(criteria.dateTo() + "까지");
        }
        if (criteria.maxPrice() != null) parts.add(String.format(Locale.KOREA, "%,d원 이하", criteria.maxPrice()));
        if (Boolean.TRUE.equals(criteria.availableOnly())) parts.add("예매 가능");
        return "검색 조건: " + (parts.isEmpty() ? "전체 공연" : String.join(" · ", parts));
    }

    private synchronized void reserveRequest() {
        // 한도는 서버(인스턴스)마다 따로 센다. 서버를 여러 대로 늘리면 Redis 같은 공유 저장소로 옮긴다.
        long now = System.nanoTime();
        while (!recentRequests.isEmpty() && now - recentRequests.peekFirst() >= TimeUnit.MINUTES.toNanos(1)) {
            recentRequests.removeFirst();
        }
        if (recentRequests.size() >= 10) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "CHAT_RATE_LIMITED", "AI 검색 요청이 많습니다. 1분 후 다시 시도해 주세요.");
        }
        recentRequests.addLast(now);
    }
}
