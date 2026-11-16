package wamddu.backend.queue;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import wamddu.backend.global.exception.ApiException;
import wamddu.backend.ticket.repository.TicketRepository;

/**
 * Checks the queue pass before an order reaches the ticket row lock.
 *
 * Must run outside the order transaction: OrderService.createOrder locks the ticket as its first read so that
 * REPEATABLE READ takes its snapshot after earlier orders committed (the last-ticket bug). Reading the event here
 * inside that transaction would fix the snapshot too early again. The repository call runs in its own transaction.
 */
@Component
public class QueueGate {
    private final TicketRepository tickets;
    private final WaitingQueue queue;
    private final Counter rejected;

    public QueueGate(TicketRepository tickets, WaitingQueue queue, MeterRegistry meters) {
        this.tickets = tickets;
        this.queue = queue;
        this.rejected = Counter.builder("freeticket.queue.rejected")
                .description("Orders refused because the user had no queue pass").register(meters);
    }

    public void requirePass(Long ticketId, Long userId) {
        var target = tickets.findQueueTarget(ticketId).orElse(null);
        // A missing ticket is reported by the order itself (TICKET_NOT_FOUND).
        if (target == null || !target.isQueueEnabled()) return;
        if (!queue.hasPass(target.getEventId(), userId)) {
            rejected.increment();
            throw new ApiException(HttpStatus.CONFLICT, "QUEUE_REQUIRED", "대기열 순서가 된 뒤에 예매할 수 있습니다.");
        }
    }
}
