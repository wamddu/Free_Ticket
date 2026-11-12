package wamddu.backend.ticket.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import wamddu.backend.global.exception.ApiException;
import wamddu.backend.order.domain.OrderStatus;
import wamddu.backend.order.repository.OrderRepository;
import wamddu.backend.ticket.domain.Ticket;
import wamddu.backend.ticket.domain.TicketPriceHistory;
import wamddu.backend.ticket.dto.request.TicketPriceSettingsRequest;
import wamddu.backend.ticket.dto.response.SellingTicketResponse;
import wamddu.backend.ticket.repository.TicketPriceHistoryRepository;
import wamddu.backend.ticket.repository.TicketRepository;

@Service
@RequiredArgsConstructor
public class TicketPricingService {
    static final Duration OBSERVATION_PERIOD = Duration.ofHours(2);
    private final TicketRepository tickets;
    private final OrderRepository orders;
    private final TicketPriceHistoryRepository history;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SellingTicketResponse configure(Long userId, Long ticketId, TicketPriceSettingsRequest request) {
        var ticket = tickets.findManagedTicketForUpdate(ticketId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND",
                        "관리할 수 있는 티켓을 찾을 수 없습니다."));
        var now = LocalDateTime.now();
        if (ticket.getInitialPrice() != null && request.initialPrice() != null
                && !ticket.getInitialPrice().equals(request.initialPrice())) {
            throw invalidSettings("한번 저장한 최초 가격은 변경할 수 없습니다.");
        }
        Integer initial = ticket.getInitialPrice() == null ? request.initialPrice() : ticket.getInitialPrice();
        Integer floor = request.minPrice();
        boolean automatic = request.automaticPricingEnabled();
        Integer price = automatic ? (ticket.getInitialPrice() == null ? initial : ticket.getPrice())
                : request.price() == null ? ticket.getPrice() : request.price();
        if ((initial != null && initial <= 0) || (floor != null && floor <= 0)
                || (initial != null && floor != null && floor > initial) || price == null || price <= 0
                || (automatic && (initial == null || floor == null || price < floor || price > initial))) {
            throw invalidSettings("가격은 1원 이상의 정수이며 하한가와 최초 가격 범위를 지켜야 합니다.");
        }
        if (automatic && (ticket.getBookingEndtime() == null || !ticket.getBookingEndtime().isAfter(now)
                || !validInventory(ticket) || ticket.getSold_ticket() >= ticket.getTotal_ticket())) {
            throw invalidSettings("판매 가능한 티켓에만 자동 가격 조정을 설정할 수 있습니다.");
        }
        Integer previousPrice = ticket.getPrice();
        if (automatic && (!ticket.isAutomaticPricingEnabled() || ticket.getSalesStartAt() == null)) {
            ticket.setSalesStartAt(now);
            ticket.setLastPriceEvaluatedAt(null);
        }
        ticket.setInitialPrice(initial);
        ticket.setMinPrice(floor);
        ticket.setAutomaticPricingEnabled(automatic);
        ticket.setPrice(price);
        if (!history.existsByTicketId(ticketId) || !Objects.equals(previousPrice, price)) {
            history.save(new TicketPriceHistory(ticket, price, now));
        }
        return SellingTicketResponse.from(ticket);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void adjustPrice(Long ticketId, LocalDateTime now) {
        var ticket = tickets.findByIdForUpdate(ticketId).orElse(null);
        var cutoff = now.minus(OBSERVATION_PERIOD);
        if (ticket == null || !ticket.isAutomaticPricingEnabled() || !validInventory(ticket)
                || ticket.getBookingEndtime() == null || !ticket.getBookingEndtime().isAfter(now)
                || ticket.getSold_ticket() >= ticket.getTotal_ticket()
                || ticket.getSalesStartAt() == null || ticket.getSalesStartAt().isAfter(cutoff)
                || (ticket.getLastPriceEvaluatedAt() != null && ticket.getLastPriceEvaluatedAt().isAfter(cutoff))) {
            return;
        }
        if (ticket.getInitialPrice() == null || ticket.getMinPrice() == null || ticket.getPrice() == null
                || ticket.getMinPrice() <= 0 || ticket.getPrice() < ticket.getMinPrice()
                || ticket.getPrice() > ticket.getInitialPrice()) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_TICKET_DATA", "자동 가격 설정이 올바르지 않습니다.");
        }
        ticket.setLastPriceEvaluatedAt(now);
        long held = orders.sumActiveQuantity(ticketId,
                List.of(OrderStatus.PENDING, OrderStatus.CONFIRMING, OrderStatus.CANCELING), now);
        if (held >= (long) ticket.getTotal_ticket() - ticket.getSold_ticket()) return;
        long sold = orders.sumPaidQuantity(ticketId, cutoff, now);
        int nextPrice = calculateNextPrice(ticket, sold, now);
        if (nextPrice != ticket.getPrice()) {
            ticket.setPrice(nextPrice);
            history.save(new TicketPriceHistory(ticket, nextPrice, now));
        }
    }

    static int calculateNextPrice(Ticket ticket, long sold, LocalDateTime now) {
        double remainingSeconds = Duration.between(now, ticket.getBookingEndtime()).toMillis() / 1000.0;
        long remaining = (long) ticket.getTotal_ticket() - ticket.getSold_ticket();
        double pace = sold * remainingSeconds / (OBSERVATION_PERIOD.toSeconds() * remaining);
        int current = ticket.getPrice();
        if (pace >= 1 || current == ticket.getMinPrice()) return current;
        double totalSeconds = Duration.between(ticket.getSalesStartAt(), ticket.getBookingEndtime()).toMillis() / 1000.0;
        if (remainingSeconds <= OBSERVATION_PERIOD.toSeconds() || remainingSeconds <= totalSeconds * 0.1) {
            return ticket.getMinPrice();
        }
        // 인하 폭(2/5/10%)은 고정값이다. 최적 가격이라고 하려면 실제 판매 데이터로 보정해야 한다.
        int discount = pace >= 0.8 ? 2 : pace >= 0.5 ? 5 : 10;
        return Math.max(ticket.getMinPrice(), (int) ((long) current * (100 - discount) / 100));
    }

    private static boolean validInventory(Ticket ticket) {
        return ticket.getTotal_ticket() != null && ticket.getSold_ticket() != null
                && ticket.getTotal_ticket() >= 0 && ticket.getSold_ticket() >= 0
                && ticket.getSold_ticket() <= ticket.getTotal_ticket();
    }

    private static ApiException invalidSettings(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PRICE_SETTINGS", message);
    }
}
