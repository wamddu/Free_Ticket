package wamddu.backend.queue;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import wamddu.backend.event.repository.EventRepository;
import wamddu.backend.global.exception.ApiException;
import wamddu.backend.global.response.ApiResponse;

@RestController
@RequestMapping("/api/queue")
@RequiredArgsConstructor
public class QueueController {
    private final WaitingQueue queue;
    private final EventRepository events;

    /** Joins the line once; calling again keeps the original place. */
    @PostMapping("/{eventId}")
    public ApiResponse<QueueStatus> enter(@PathVariable Long eventId, @AuthenticationPrincipal UserDetails principal) {
        var event = events.findById(eventId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND", "공연을 찾을 수 없습니다."));
        if (!event.isQueueEnabled()) {
            return ApiResponse.success("대기열 없이 예매할 수 있습니다.", QueueStatus.notRequired());
        }
        return ApiResponse.success("대기열에 등록했습니다.", queue.enter(eventId, userId(principal)));
    }

    /** Polled by the waiting page; reads Redis only. */
    @GetMapping("/{eventId}")
    public ApiResponse<QueueStatus> status(@PathVariable Long eventId, @AuthenticationPrincipal UserDetails principal) {
        QueueStatus status = queue.status(eventId, userId(principal));
        if (status == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "QUEUE_NOT_JOINED", "대기열에 등록되어 있지 않습니다.");
        }
        return ApiResponse.success("대기 순서를 조회했습니다.", status);
    }

    private Long userId(UserDetails principal) {
        return Long.parseLong(principal.getUsername());
    }
}
