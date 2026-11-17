package wamddu.backend.queue;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import wamddu.backend.global.exception.ApiException;
import wamddu.backend.ticket.repository.TicketRepository;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/** Entry tokens from the edge waiting room (infra/edge/lambda/waiting_room.py). */
class QueueGateTest {
    static final String SECRET = "edge-token-secret-for-tests-0123456789abcdef";
    // Made by waiting_room.queue_token(7, 107, 4102444800) with SECRET: the Lambda and the server must agree.
    static final String LAMBDA_TOKEN = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiI3IiwiZWlkIjoxMDcsImV4cCI6NDEwMjQ0NDgwMH0."
            + "WVWMsyEXW_FdhAM6gGt81UiTIu-JBfWBNfW8LLkFO8M";

    final TicketRepository tickets = mock(TicketRepository.class);
    final WaitingQueue queue = mock(WaitingQueue.class);
    QueueGate gate;

    @BeforeEach
    void setUp() {
        when(tickets.findQueueTarget(70L)).thenReturn(Optional.of(target(107L, true)));
        when(tickets.findQueueTarget(80L)).thenReturn(Optional.of(target(108L, false)));
        when(queue.hasPass(anyLong(), anyLong())).thenReturn(false);
        gate = new QueueGate(tickets, queue, new SimpleMeterRegistry(), SECRET);
    }

    @Test
    void tokenSignedByTheLambdaIsAccepted() {
        gate.requirePass(70L, 7L, LAMBDA_TOKEN);
        verify(queue, never()).hasPass(anyLong(), anyLong());
    }

    @Test
    void tokenForAnotherUserOrEventIsRefused() {
        assertRefused(70L, 8L, LAMBDA_TOKEN);
        assertRefused(70L, 7L, token(SECRET, "7", 999, 60));
    }

    @Test
    void expiredForgedOrMissingTokensAreRefused() {
        assertRefused(70L, 7L, token(SECRET, "7", 107, -1));
        assertRefused(70L, 7L, token("another-secret-that-is-long-enough-0123456789", "7", 107, 60));
        assertRefused(70L, 7L, "not-a-token");
        assertRefused(70L, 7L, null);
    }

    @Test
    void redisPassStillWorksWithoutAToken() {
        when(queue.hasPass(107L, 7L)).thenReturn(true);
        gate.requirePass(70L, 7L, null);
    }

    @Test
    void eventsWithoutQueueNeedNothing() {
        gate.requirePass(80L, 7L, null);
        verifyNoInteractions(queue);
    }

    @Test
    void withoutAnEdgeSecretTokensAreIgnored() {
        QueueGate noEdge = new QueueGate(tickets, queue, new SimpleMeterRegistry(), "");
        assertThat(noEdge.validEdgeToken(LAMBDA_TOKEN, 7L, 107L)).isFalse();
    }

    void assertRefused(Long ticketId, Long userId, String token) {
        assertThatThrownBy(() -> gate.requirePass(ticketId, userId, token))
                .isInstanceOf(ApiException.class).hasMessageContaining("대기열");
    }

    static String token(String secret, String subject, long eventId, long secondsLeft) {
        return Jwts.builder().subject(subject).claim("eid", eventId)
                .expiration(new Date(System.currentTimeMillis() + secondsLeft * 1000))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256).compact();
    }

    static TicketRepository.QueueTarget target(Long eventId, boolean enabled) {
        return new TicketRepository.QueueTarget() {
            public Long getEventId() { return eventId; }
            public boolean isQueueEnabled() { return enabled; }
        };
    }
}
