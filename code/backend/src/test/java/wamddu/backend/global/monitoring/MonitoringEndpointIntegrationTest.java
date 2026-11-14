package wamddu.backend.global.monitoring;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import wamddu.backend.event.domain.Event;
import wamddu.backend.order.domain.OrderStatus;
import wamddu.backend.order.dto.request.CreateOrderRequest;
import wamddu.backend.order.service.OrderService;
import wamddu.backend.payment.client.TossPaymentsClient;
import wamddu.backend.ticket.domain.Ticket;
import wamddu.backend.user.domain.User;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Metrics and health are served on the management port only, and the payment gauges follow the database. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:monitoring_test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "management.server.port=0",
        // Spring Boot tests turn metrics export off by default; this test checks the export itself.
        "management.prometheus.metrics.export.enabled=true"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MonitoringEndpointIntegrationTest {
    @LocalServerPort int appPort;
    @LocalManagementPort int managementPort;
    @Autowired OrderService orderService;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager manager;
    @MockitoBean TossPaymentsClient toss;
    TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(manager);
    }

    @Test
    void healthIsUpWithoutDetails() {
        String body = management().get().uri("/actuator/health").retrieve().body(String.class);
        assertThat(body).isEqualTo("{\"groups\":[\"liveness\",\"readiness\"],\"status\":\"UP\"}");
    }

    @Test
    void prometheusShowsTomcatAndLatencyBuckets() {
        // Only requests on the application port are timed; the management port is not.
        RestClient.create("http://localhost:" + appPort).get().uri("/api/events")
                .exchange((request, response) -> response.getStatusCode());
        String metrics = scrape();
        assertThat(metrics)
                .contains("tomcat_connections_config_max_connections")
                .contains("hikaricp_connections_active")
                .contains("http_server_requests_seconds_bucket{application=\"free-ticket\"")
                .contains("uri=\"/api/events\"")
                .contains("application=\"free-ticket\"");
    }

    @Test
    void actuatorIsNotServedOnTheApplicationPort() {
        HttpStatusCode status = RestClient.create("http://localhost:" + appPort).get()
                .uri("/actuator/prometheus")
                .exchange((request, response) -> response.getStatusCode());
        assertThat(status.value()).isEqualTo(404);
    }

    @Test
    void paymentGaugesCountUnfinishedAndReviewOrders() {
        List<String> orderIds = createOrders(4);
        mark(orderIds.get(0), OrderStatus.CONFIRMING, false);
        mark(orderIds.get(1), OrderStatus.CONFIRMING, false);
        mark(orderIds.get(2), OrderStatus.CANCELING, false);
        mark(orderIds.get(3), OrderStatus.CANCELING, true);

        String metrics = scrape();
        assertThat(gauge(metrics, "freeticket_payments_unfinished{application=\"free-ticket\",state=\"confirming\"}")).isEqualTo(2.0);
        assertThat(gauge(metrics, "freeticket_payments_unfinished{application=\"free-ticket\",state=\"canceling\"}")).isEqualTo(1.0);
        assertThat(gauge(metrics, "freeticket_payments_review_required{application=\"free-ticket\"}")).isEqualTo(1.0);
        assertThat(gauge(metrics, "freeticket_payments_reconciliation_open{application=\"free-ticket\"}")).isEqualTo(0.0);
    }

    private RestClient management() {
        return RestClient.create("http://localhost:" + managementPort);
    }

    private String scrape() {
        return management().get().uri("/actuator/prometheus").retrieve().body(String.class);
    }

    private double gauge(String metrics, String series) {
        return metrics.lines()
                .filter(line -> line.startsWith(series + " "))
                .map(line -> Double.parseDouble(line.substring(series.length() + 1).trim()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing " + series + " in\n" + metrics));
    }

    private List<String> createOrders(int count) {
        Long[] ids = tx.execute(s -> {
            for (String entity : List.of("Payment", "Order", "Ticket", "Event", "User")) {
                em.createQuery("delete from " + entity).executeUpdate();
            }
            User buyer = User.builder().username("Metrics buyer").password("test-only")
                    .email("metrics@example.test").customerKey("metrics_customer").build();
            em.persist(buyer);
            Event event = new Event();
            event.setName("Metrics test");
            event.setStartDate(LocalDate.now().plusDays(1));
            event.setEndDate(LocalDate.now().plusDays(2));
            event.setLocation("venue");
            event.setBannerImageUrl("banner");
            event.setMainImageUrl("main");
            em.persist(event);
            Ticket ticket = new Ticket();
            ticket.setEvent(event);
            ticket.setType("ticket");
            ticket.setPrice(10000);
            ticket.setTotal_ticket(count);
            ticket.setSold_ticket(0);
            ticket.setBookingEndtime(LocalDateTime.now().plusDays(1));
            em.persist(ticket);
            return new Long[]{buyer.getId(), ticket.getId()};
        });
        List<String> orderIds = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            var create = new CreateOrderRequest();
            create.setTicketId(ids[1]);
            create.setQuantity(1);
            orderIds.add(orderService.createOrder(create, ids[0]).orderId());
        }
        return orderIds;
    }

    private void mark(String orderId, OrderStatus status, boolean reviewRequired) {
        tx.executeWithoutResult(s -> em.createQuery(
                        "update Order o set o.status = :status, o.recoveryReviewRequired = :review where o.orderId = :id")
                .setParameter("status", status)
                .setParameter("review", reviewRequired)
                .setParameter("id", orderId)
                .executeUpdate());
    }
}
