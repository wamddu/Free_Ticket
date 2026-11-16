package wamddu.backend.queue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import wamddu.backend.event.domain.Event;
import wamddu.backend.global.security.JwtProvider;
import wamddu.backend.ticket.domain.Ticket;
import wamddu.backend.ticket.repository.TicketRepository;
import wamddu.backend.user.domain.User;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The waiting queue against a real Redis (docs/design/waiting-queue.md).
 * Runs where Redis listens on localhost:6379, as in CI: RUN_REDIS_TESTS=true.
 */
@EnabledIfEnvironmentVariable(named = "RUN_REDIS_TESTS", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:waiting_queue_test;MODE=MySQL;DB_CLOSE_DELAY=-1"
})
@DirtiesContext
class WaitingQueueRedisTest {
    static final Clock SECOND_1000 = Clock.fixed(Instant.ofEpochSecond(1000), ZoneOffset.UTC);

    @Value("${local.server.port}") int port;
    @Autowired StringRedisTemplate redis;
    @Autowired WaitingQueue queue;
    @Autowired JwtProvider jwt;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager manager;
    @MockitoSpyBean TicketRepository tickets;

    final HttpClient http = HttpClient.newHttpClient();
    final ObjectMapper json = new ObjectMapper();
    TransactionTemplate tx;
    List<Long> users;
    long queuedEvent;
    long queuedTicket;
    long openTicket;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(manager);
        Set<String> keys = redis.keys("queue:*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        tx.executeWithoutResult(s -> {
            for (String entity : List.of("Payment", "Order", "Ticket", "Event", "User")) {
                em.createQuery("delete from " + entity).executeUpdate();
            }
        });
        users = tx.execute(s -> {
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < 120; i++) {
                User user = User.builder().username("q" + i).password("test-only")
                        .email("queue" + i + "@example.test").customerKey("queue_customer_" + i).build();
                em.persist(user);
                ids.add(user.getId());
            }
            return ids;
        });
        long[] ids = tx.execute(s -> {
            Event hot = event("Hot opening", true);
            Event normal = event("Normal", false);
            return new long[]{hot.getId(), ticket(hot), ticket(normal)};
        });
        queuedEvent = ids[0];
        queuedTicket = ids[1];
        openTicket = ids[2];
        clearInvocations(tickets);
    }

    @Test
    void usersAreAdmittedInTheOrderTheyJoined() {
        WaitingQueue tenPerSecond = queueWith(10, SECOND_1000);
        for (int i = 0; i < 30; i++) tenPerSecond.enter(queuedEvent, users.get(i));

        assertThat(tenPerSecond.admitAll()).isEqualTo(10);

        for (int i = 0; i < 10; i++) assertThat(tenPerSecond.hasPass(queuedEvent, users.get(i))).isTrue();
        assertThat(tenPerSecond.hasPass(queuedEvent, users.get(10))).isFalse();
        QueueStatus next = tenPerSecond.status(queuedEvent, users.get(10));
        assertThat(next.admitted()).isFalse();
        assertThat(next.position()).isEqualTo(1);
        assertThat(next.estimatedWaitSeconds()).isEqualTo(1);
        assertThat(tenPerSecond.status(queuedEvent, users.get(29)).position()).isEqualTo(20);
    }

    @Test
    void joiningAgainKeepsTheOriginalPlace() {
        for (int i = 0; i < 5; i++) queue.enter(queuedEvent, users.get(i));

        QueueStatus again = queue.enter(queuedEvent, users.get(2));

        assertThat(again.position()).isEqualTo(3);
        assertThat(queue.status(queuedEvent, users.get(4)).position()).isEqualTo(5);
    }

    @Test
    void concurrentJoinsGetDistinctPlaces() throws Exception {
        List<Callable<Long>> joins = new ArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < 100; i++) {
            long user = users.get(i);
            joins.add(() -> {
                start.await();
                return queue.enter(queuedEvent, user).position();
            });
        }
        var pool = Executors.newFixedThreadPool(32);
        try {
            List<Future<Long>> results = new ArrayList<>();
            for (Callable<Long> join : joins) results.add(pool.submit(join));
            start.countDown();
            Set<Long> positions = new HashSet<>();
            for (Future<Long> result : results) positions.add(result.get(10, TimeUnit.SECONDS));
            assertThat(positions).hasSize(100).allMatch(p -> p >= 1 && p <= 100);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void twoServersAdmittingInTheSameSecondLetOnlyNIn() throws Exception {
        for (int i = 0; i < 50; i++) queue.enter(queuedEvent, users.get(i));
        WaitingQueue serverA = queueWith(10, SECOND_1000);
        WaitingQueue serverB = queueWith(10, SECOND_1000);
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = pool.submit(() -> { start.await(); return serverA.admitAll(); });
            Future<Integer> b = pool.submit(() -> { start.await(); return serverB.admitAll(); });
            start.countDown();
            assertThat(a.get(5, TimeUnit.SECONDS) + b.get(5, TimeUnit.SECONDS)).isEqualTo(10);
        } finally {
            pool.shutdownNow();
        }
        WaitingQueue nextSecond = queueWith(10, Clock.fixed(Instant.ofEpochSecond(1001), ZoneOffset.UTC));
        assertThat(nextSecond.admitAll()).isEqualTo(10);
    }

    @Test
    void statusNeverLosesAUserWhileAdmissionRuns() throws Exception {
        // Load test 2026-11-17: 0.8% of polls answered "not in line" when admission ran between the two reads.
        int people = 100;
        for (int i = 0; i < people; i++) queue.enter(queuedEvent, users.get(i));
        var pool = Executors.newFixedThreadPool(5);
        try {
            Future<?> admitting = pool.submit(() -> {
                for (int second = 0; second < 50; second++) {
                    queueWith(2, Clock.fixed(Instant.ofEpochSecond(2000 + second), ZoneOffset.UTC)).admitAll();
                }
            });
            List<Future<Integer>> pollers = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                pollers.add(pool.submit(() -> {
                    int lost = 0;
                    while (!admitting.isDone()) {
                        for (int i = 0; i < people; i++) {
                            if (queue.status(queuedEvent, users.get(i)) == null) lost++;
                        }
                    }
                    return lost;
                }));
            }
            admitting.get(30, TimeUnit.SECONDS);
            for (Future<Integer> poller : pollers) assertThat(poller.get(30, TimeUnit.SECONDS)).isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void orderWithoutPassIsRefusedBeforeTheTicketLock() throws Exception {
        queue.enter(queuedEvent, users.get(0));

        JsonNode response = order(users.get(0), queuedTicket, 409);

        assertThat(response.path("code").asString()).isEqualTo("QUEUE_REQUIRED");
        verify(tickets, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void admittedUserCanOrder() throws Exception {
        queue.enter(queuedEvent, users.get(0));
        queueWith(20, SECOND_1000).admitAll();

        JsonNode response = order(users.get(0), queuedTicket, 201);

        assertThat(response.path("data").path("orderId").asString()).isNotBlank();
    }

    @Test
    void expiredPassNoLongerAllowsOrdering() throws Exception {
        redis.opsForValue().set(WaitingQueue.pass(queuedEvent, users.get(0)), "1", java.time.Duration.ofSeconds(1));
        Thread.sleep(1500);

        assertThat(order(users.get(0), queuedTicket, 409).path("code").asString()).isEqualTo("QUEUE_REQUIRED");
    }

    @Test
    void eventsWithoutQueueOrderAsBefore() throws Exception {
        order(users.get(0), openTicket, 201);
    }

    @Test
    void waitingPageApiReportsPositionThenAdmission() throws Exception {
        JsonNode joined = call("POST", "/api/queue/" + queuedEvent, users.get(0), 200).path("data");
        assertThat(joined.path("admitted").asBoolean()).isFalse();
        assertThat(joined.path("position").asLong()).isEqualTo(1);
        assertThat(joined.path("nextPollSeconds").asInt()).isEqualTo(2);

        queueWith(20, SECOND_1000).admitAll();

        JsonNode admitted = call("GET", "/api/queue/" + queuedEvent, users.get(0), 200).path("data");
        assertThat(admitted.path("admitted").asBoolean()).isTrue();
        assertThat(admitted.path("passExpiresInSeconds").asLong()).isBetween(1L, 300L);
        assertThat(call("GET", "/api/queue/" + queuedEvent, users.get(1), 404).path("code").asString())
                .isEqualTo("QUEUE_NOT_JOINED");
    }

    WaitingQueue queueWith(int perSecond, Clock clock) {
        return new WaitingQueue(redis, new SimpleMeterRegistry(), perSecond, 300, clock);
    }

    JsonNode order(long userId, long ticketId, int expectedStatus) throws Exception {
        return call("POST", "/api/orders", userId, expectedStatus, "{\"ticketId\":" + ticketId + ",\"quantity\":1}");
    }

    JsonNode call(String method, String path, long userId, int expectedStatus) throws Exception {
        return call(method, path, userId, expectedStatus, null);
    }

    JsonNode call(String method, String path, long userId, int expectedStatus, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + jwt.generateJwtToken(userId, "USER"))
                .header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expectedStatus);
        return json.readTree(response.body());
    }

    Event event(String name, boolean queueEnabled) {
        Event event = new Event();
        event.setName(name);
        event.setStartDate(LocalDate.now().plusDays(7));
        event.setEndDate(LocalDate.now().plusDays(8));
        event.setLocation("venue");
        event.setBannerImageUrl("banner");
        event.setMainImageUrl("main");
        event.setQueueEnabled(queueEnabled);
        em.persist(event);
        return event;
    }

    long ticket(Event event) {
        Ticket ticket = new Ticket();
        ticket.setEvent(event);
        ticket.setType("R");
        ticket.setPrice(10000);
        ticket.setTotal_ticket(1000);
        ticket.setSold_ticket(0);
        ticket.setBookingEndtime(LocalDateTime.now().plusDays(5));
        em.persist(ticket);
        return ticket.getId();
    }
}
