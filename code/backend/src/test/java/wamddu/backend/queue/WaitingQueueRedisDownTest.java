package wamddu.backend.queue;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import wamddu.backend.event.domain.Event;
import wamddu.backend.global.security.JwtProvider;
import wamddu.backend.ticket.domain.Ticket;
import wamddu.backend.user.domain.User;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With Redis down the queue lets everyone through (fail-open): the ticket row lock still prevents overselling,
 * and blocking every order because of the queue store would stop all bookings.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:waiting_queue_down_test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.data.redis.port=1",
        "spring.data.redis.connect-timeout=200ms",
        "spring.data.redis.timeout=500ms"
})
@DirtiesContext
class WaitingQueueRedisDownTest {
    @Value("${local.server.port}") int port;
    @Autowired JwtProvider jwt;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager manager;

    @Test
    void queueEventStillTakesOrdersWhenRedisIsDown() throws Exception {
        long[] ids = new TransactionTemplate(manager).execute(s -> {
            User user = User.builder().username("down").password("test-only")
                    .email("queue-down@example.test").customerKey("queue_down").build();
            em.persist(user);
            Event event = new Event();
            event.setName("Hot opening");
            event.setStartDate(LocalDate.now().plusDays(7));
            event.setEndDate(LocalDate.now().plusDays(8));
            event.setLocation("venue");
            event.setBannerImageUrl("banner");
            event.setMainImageUrl("main");
            event.setQueueEnabled(true);
            em.persist(event);
            Ticket ticket = new Ticket();
            ticket.setEvent(event);
            ticket.setType("R");
            ticket.setPrice(10000);
            ticket.setTotal_ticket(10);
            ticket.setSold_ticket(0);
            ticket.setBookingEndtime(LocalDateTime.now().plusDays(5));
            em.persist(ticket);
            return new long[]{user.getId(), event.getId(), ticket.getId()};
        });
        String token = jwt.generateJwtToken(ids[0], "USER");
        HttpClient http = HttpClient.newHttpClient();
        ObjectMapper json = new ObjectMapper();

        HttpResponse<String> joined = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/queue/" + ids[1]))
                .header("Authorization", "Bearer " + token).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        JsonNode status = json.readTree(joined.body()).path("data");
        assertThat(joined.statusCode()).isEqualTo(200);
        assertThat(status.path("admitted").asBoolean()).isTrue();
        assertThat(status.path("bypassed").asBoolean()).isTrue();

        HttpResponse<String> order = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/orders"))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"ticketId\":" + ids[2] + ",\"quantity\":1}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(order.statusCode()).as(order.body()).isEqualTo(201);
    }
}
