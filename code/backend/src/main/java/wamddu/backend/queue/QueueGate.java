package wamddu.backend.queue;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import wamddu.backend.global.exception.ApiException;
import wamddu.backend.ticket.repository.TicketRepository;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * Checks the queue pass before an order reaches the ticket row lock.
 *
 * Two kinds of pass:
 * - an entry token from the edge waiting room (CloudFront + Lambda, infra/edge): checked by signature only,
 *   so admitted users cost the server nothing beyond the order itself;
 * - the server's own Redis queue pass (local runs, or when the edge is not in front).
 *
 * Must run outside the order transaction: OrderService.createOrder locks the ticket as its first read so that
 * REPEATABLE READ takes its snapshot after earlier orders committed (the last-ticket bug). Reading the event here
 * inside that transaction would fix the snapshot too early again. The repository call runs in its own transaction.
 */
@Component
public class QueueGate {
    private final TicketRepository tickets;
    private final WaitingQueue queue;
    private final SecretKey edgeKey;
    private final Counter rejected;
    private final Counter edgeAccepted;

    public QueueGate(TicketRepository tickets, WaitingQueue queue, MeterRegistry meters,
                     @Value("${queue.edge.token-secret:}") String edgeSecret) {
        this.tickets = tickets;
        this.queue = queue;
        // Empty: no edge waiting room in front of this server, so only Redis passes count.
        this.edgeKey = edgeSecret.isBlank() ? null : Keys.hmacShaKeyFor(edgeSecret.getBytes(StandardCharsets.UTF_8));
        this.rejected = Counter.builder("freeticket.queue.rejected")
                .description("Orders refused because the user had no queue pass").register(meters);
        this.edgeAccepted = Counter.builder("freeticket.queue.edge.accepted")
                .description("Orders let in with an edge waiting room token").register(meters);
    }

    public void requirePass(Long ticketId, Long userId, String edgeToken) {
        var target = tickets.findQueueTarget(ticketId).orElse(null);
        // A missing ticket is reported by the order itself (TICKET_NOT_FOUND).
        if (target == null || !target.isQueueEnabled()) return;
        if (validEdgeToken(edgeToken, userId, target.getEventId())) {
            edgeAccepted.increment();
            return;
        }
        if (!queue.hasPass(target.getEventId(), userId)) {
            rejected.increment();
            throw new ApiException(HttpStatus.CONFLICT, "QUEUE_REQUIRED", "대기열 순서가 된 뒤에 예매할 수 있습니다.");
        }
    }

    /** Signed by the edge waiting room for this user and event, not expired. */
    boolean validEdgeToken(String token, Long userId, Long eventId) {
        if (edgeKey == null || token == null || token.isBlank()) return false;
        try {
            Claims claims = Jwts.parser().verifyWith(edgeKey).build().parseSignedClaims(token).getPayload();
            Number event = claims.get("eid", Number.class);
            return String.valueOf(userId).equals(claims.getSubject()) && event != null && event.longValue() == eventId;
        } catch (JwtException | IllegalArgumentException invalid) {
            return false;
        }
    }
}
