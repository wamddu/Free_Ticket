package wamddu.backend.order.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import wamddu.backend.global.exception.ApiException;
import wamddu.backend.order.domain.*;
import wamddu.backend.order.dto.request.*;
import wamddu.backend.order.dto.response.*;
import wamddu.backend.order.repository.OrderRepository;
import wamddu.backend.payment.domain.Payment;
import wamddu.backend.payment.repository.PaymentRepository;
import wamddu.backend.ticket.domain.Ticket;
import wamddu.backend.ticket.repository.TicketRepository;
import wamddu.backend.user.domain.User;
import wamddu.backend.user.repository.UserRepository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {
    private static final DateTimeFormatter ORDER_ID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final int PAYMENT_WINDOW_MINUTES = 10;
    /** Unpaid quantity one user may hold on a ticket, so a single buyer cannot block a sale. */
    static final int MAX_HELD_PER_USER = 10;
    private static final List<OrderStatus> HOLDING_STATUSES =
            List.of(OrderStatus.PENDING, OrderStatus.CONFIRMING, OrderStatus.CANCELING);

    private final OrderRepository orderRepository;
    private final TicketRepository ticketRepository;
    private final UserRepository userRepository;
    private final PaymentRepository paymentRepository;

    @Transactional
    public CheckoutOrderResponse createOrder(CreateOrderRequest request, Long userId) {
        Ticket ticket = ticketRepository.findByIdForUpdate(request.getTicketId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND", "존재하지 않는 티켓입니다."));
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요합니다."));

        LocalDateTime now = LocalDateTime.now();
        if (ticket.getBookingEndtime() == null || !now.isBefore(ticket.getBookingEndtime())) {
            throw new ApiException(HttpStatus.CONFLICT, "BOOKING_CLOSED", "예매가 마감된 티켓입니다.");
        }
        if (ticket.getPrice() == null || ticket.getPrice() < 0
                || ticket.getTotal_ticket() == null || ticket.getSold_ticket() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_TICKET_DATA", "티켓 판매 정보가 올바르지 않습니다.");
        }

        // The ticket row lock above serializes this check, so concurrent orders cannot exceed the limit.
        long heldByUser = orderRepository.sumActiveQuantityByUser(ticket.getId(), userId, HOLDING_STATUSES, now);
        if (heldByUser + request.getQuantity() > MAX_HELD_PER_USER) {
            throw new ApiException(HttpStatus.CONFLICT, "ORDER_LIMIT_EXCEEDED",
                    "한 티켓은 결제 대기 중인 수량을 합쳐 " + MAX_HELD_PER_USER + "장까지 주문할 수 있습니다. 진행 중인 주문을 결제하거나 결제 시간이 지난 뒤 다시 시도해 주세요.");
        }

        long pendingQuantity = orderRepository.sumActiveQuantity(ticket.getId(), HOLDING_STATUSES, now);
        long remaining = (long) ticket.getTotal_ticket() - ticket.getSold_ticket() - pendingQuantity;
        if (remaining < request.getQuantity()) {
            throw new ApiException(HttpStatus.CONFLICT, "TICKET_SOLD_OUT", "선택한 수량만큼 남은 티켓이 없습니다.");
        }

        if (user.getCustomerKey() == null || user.getCustomerKey().isBlank()) {
            user.setCustomerKey("customer_" + compactUuid());
        }

        Order order = Order.createPendingOrder(
                generateOrderId(),
                user,
                ticket.getId(),
                ticket.getEvent().getId(),
                request.getQuantity(),
                ticket.getPrice(),
                UUID.randomUUID().toString(),
                PAYMENT_WINDOW_MINUTES
        );
        orderRepository.save(order);

        return toCheckoutResponse(order, ticket);
    }

    @Transactional(readOnly = true)
    public CheckoutOrderResponse getCheckoutOrder(String orderId, Long userId) {
        Order order = orderRepository.findByOrderIdAndUserId(orderId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "주문을 찾을 수 없습니다."));
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_ORDER_STATUS", "결제를 진행할 수 없는 주문입니다.");
        }
        if (LocalDateTime.now().isAfter(order.getExpiresAt())) {
            throw new ApiException(HttpStatus.CONFLICT, "ORDER_EXPIRED", "결제 가능 시간이 만료되었습니다.");
        }
        Ticket ticket = ticketRepository.findById(order.getTicket_id())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND", "티켓을 찾을 수 없습니다."));
        return toCheckoutResponse(order, ticket);
    }

    @Transactional(readOnly = true)
    public ReservationListResponse getMyReservations(Long userId) {
        List<Payment> payments = paymentRepository.findAllReservationsByUserId(userId,
                List.of(OrderStatus.PAID, OrderStatus.CANCELING));
        List<Long> ticketIds = payments.stream()
                .map(payment -> payment.getOrder().getTicket_id())
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Map<Long, Ticket> ticketsById = ticketIds.isEmpty()
                ? Map.of()
                : ticketRepository.findAllWithEventByIdIn(ticketIds).stream()
                        .collect(Collectors.toMap(Ticket::getId, Function.identity()));

        List<ReservationHistoryResponse> list = payments.stream()
                .map(payment -> {
                    Long ticketId = payment.getOrder().getTicket_id();
                    return ReservationHistoryResponse.of(payment,
                            ticketId == null ? null : ticketsById.get(ticketId));
                })
                .toList();

        return new ReservationListResponse(list);
    }

    private CheckoutOrderResponse toCheckoutResponse(Order order, Ticket ticket) {
        return CheckoutOrderResponse.of(order, ticket);
    }

    private static String generateOrderId() {
        String timestamp = LocalDateTime.now().format(ORDER_ID_FORMAT);
        return "ORD-" + timestamp + "-" + compactUuid().substring(0, 8).toUpperCase();
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
