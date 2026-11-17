package wamddu.backend.order.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import wamddu.backend.global.response.ApiResponse;
import wamddu.backend.order.dto.request.CreateOrderRequest;
import wamddu.backend.order.dto.response.CheckoutOrderResponse;
import wamddu.backend.order.dto.response.ReservationListResponse;
import wamddu.backend.order.service.OrderService;
import wamddu.backend.queue.QueueGate;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {
    private final OrderService orderService;
    private final QueueGate queueGate;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CheckoutOrderResponse> createOrder(
            @Valid @RequestBody CreateOrderRequest request,
            @AuthenticationPrincipal UserDetails principal,
            @RequestHeader(name = "X-Queue-Token", required = false) String queueToken
    ) {
        // Outside the order transaction on purpose; see QueueGate.
        queueGate.requirePass(request.getTicketId(), userId(principal), queueToken);
        return ApiResponse.success("주문이 생성되었습니다.", orderService.createOrder(request, userId(principal)));
    }

    @GetMapping("/{orderId}/checkout")
    public ApiResponse<CheckoutOrderResponse> getCheckoutOrder(
            @PathVariable String orderId,
            @AuthenticationPrincipal UserDetails principal
    ) {
        return ApiResponse.success("결제할 주문을 조회했습니다.", orderService.getCheckoutOrder(orderId, userId(principal)));
    }

    @GetMapping("/me/reservations")
    public ApiResponse<ReservationListResponse> getMyReservations(@AuthenticationPrincipal UserDetails principal) {
        return ApiResponse.success("예매 내역을 조회했습니다.", orderService.getMyReservations(userId(principal)));
    }

    private Long userId(UserDetails principal) {
        return Long.parseLong(principal.getUsername());
    }
}
