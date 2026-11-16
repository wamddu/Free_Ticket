package wamddu.backend.event.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import wamddu.backend.event.domain.Event;

import java.time.LocalDate;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventInfoResponse {
    private Long id;
    private String name;
    private LocalDate startDate;
    private LocalDate endDate;
    private String location;
    private String bannerImageUrl;
    private String mainImageUrl;
    private String category;
    private String description;

    @JsonProperty("running_time")
    private Integer runningTime;

    private String descriptionImageUrl;

    private boolean queueEnabled;

    public static EventInfoResponse from(Event event) {
        return EventInfoResponse.builder()
                .id(event.getId())
                .name(event.getName())
                .startDate(event.getStartDate())
                .endDate(event.getEndDate())
                .location(event.getLocation())
                .bannerImageUrl(event.getBannerImageUrl())
                .mainImageUrl(event.getMainImageUrl())
                .category(event.getCategory())
                .description(event.getDescription())
                .runningTime(event.getRunning_time())
                .descriptionImageUrl(event.getDescriptionImageUrl())
                .queueEnabled(event.isQueueEnabled())
                .build();
    }
}
