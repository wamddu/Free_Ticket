package wamddu.backend.event.dto.response;

import java.util.List;

/**
 * @param period WEEKLY when ranked by the last 7 days of sales; ALL_TIME when nothing sold in that window
 *               and the ranking falls back to cumulative sales.
 */
public record WeeklyRankingResponse(String category, Period period, List<WeeklyRankingEventResponse> events) {
    public enum Period { WEEKLY, ALL_TIME }
}
