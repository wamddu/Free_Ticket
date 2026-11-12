package wamddu.backend.event.repository;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import wamddu.backend.event.domain.Event;
import wamddu.backend.order.domain.Order;
import wamddu.backend.order.domain.OrderStatus;
import wamddu.backend.payment.domain.Payment;
import wamddu.backend.user.domain.Role;
import wamddu.backend.user.domain.User;
import wamddu.backend.user.domain.UserStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Weekly ranking counts tickets paid in [from, to), using orders.paid_at. */
@SpringBootTest
@Transactional
class EventRepositoryWeeklyRankingTest {
    static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 12, 12, 0);
    static final LocalDateTime FROM = NOW.minusDays(7);

    @Autowired EventRepository events;
    @Autowired EntityManager em;
    User buyer;

    @BeforeEach
    void setUp() {
        buyer = User.builder().username("Buyer").password("test-only").email("weekly@example.test")
                .customerKey("weekly").role(Role.USER).status(UserStatus.ACTIVE).build();
        em.persist(buyer);
    }

    Event event(String name, String category, LocalDate endDate) {
        Event event = new Event();
        event.setName(name);
        event.setStartDate(LocalDate.of(2026, 9, 1));
        event.setEndDate(endDate);
        event.setLocation("Seoul");
        event.setBannerImageUrl("banner");
        event.setMainImageUrl("main-" + name);
        event.setCategory(category);
        em.persist(event);
        return event;
    }

    Event event(String name) {
        return event(name, "musical", LocalDate.of(2026, 12, 31));
    }

    Order order(Event event, int quantity, OrderStatus status, LocalDateTime paidAt) {
        Order order = Order.createPendingOrder("ORD-" + UUID.randomUUID().toString().substring(0, 12), buyer,
                1L, event.getId(), quantity, 1000, UUID.randomUUID().toString(), 10);
        order.setStatus(status);
        order.setPaidAt(paidAt);
        em.persist(order);
        return order;
    }

    void paid(Event event, int quantity, LocalDateTime paidAt) {
        Order order = order(event, quantity, OrderStatus.PAID, paidAt);
        payment(order, "DONE");
    }

    void payment(Order order, String status) {
        em.persist(Payment.createPayment(order, "pk-" + UUID.randomUUID(), order.getTotalAmount(),
                "카드", status, order.getPaidAt(), null));
    }

    java.util.List<wamddu.backend.event.dto.response.WeeklyRankingEventResponse> ranking(String category, int limit) {
        em.flush();
        em.clear();
        return events.weeklyRanking(category, FROM, NOW, limit);
    }

    @Test
    void countsOnlyTicketsPaidWithinTheLastSevenDays() {
        Event recent = event("recent");
        Event old = event("old");
        paid(recent, 2, NOW.minusDays(1));
        paid(recent, 1, FROM);                       // inclusive lower bound
        paid(old, 9, FROM.minusSeconds(1));          // just outside the window
        paid(old, 9, NOW);                           // exclusive upper bound

        var result = ranking(null, 5);

        assertThat(result).extracting("id").containsExactly(recent.getId());
        assertThat(result).extracting("rank").containsExactly(1L);
    }

    @Test
    void excludesUnpaidAndCanceledOrdersButKeepsPendingCancellations() {
        Event canceling = event("canceling");
        Event canceled = event("canceled");
        Event unpaid = event("unpaid");
        Order cancelingOrder = order(canceling, 3, OrderStatus.CANCELING, NOW.minusDays(1));
        payment(cancelingOrder, "DONE");             // refund not confirmed yet: still sold
        Order canceledOrder = order(canceled, 5, OrderStatus.CANCELED, NOW.minusDays(1));
        payment(canceledOrder, "CANCELED");
        order(unpaid, 7, OrderStatus.PENDING, null);
        order(unpaid, 7, OrderStatus.CONFIRMING, null);
        order(unpaid, 7, OrderStatus.PAYMENT_FAILED, null);

        var result = ranking(null, 5);

        assertThat(result).extracting("id").containsExactly(canceling.getId());
    }

    @Test
    void ranksBySoldQuantityWithDenseRankAndEndDateThenIdTieBreak() {
        Event top = event("top", "musical", LocalDate.of(2026, 12, 31));
        Event tieLate = event("tie late", "musical", LocalDate.of(2026, 12, 31));
        Event tieEarly = event("tie early", "musical", LocalDate.of(2026, 11, 30));
        Event last = event("last", "musical", LocalDate.of(2026, 11, 1));
        paid(top, 5, NOW.minusDays(1));
        paid(top, 2, NOW.minusDays(2));              // several orders of one event are summed
        paid(tieLate, 4, NOW.minusDays(1));
        paid(tieEarly, 4, NOW.minusDays(3));
        paid(last, 1, NOW.minusDays(1));

        var result = ranking(null, 5);

        assertThat(result).extracting("id")
                .containsExactly(top.getId(), tieEarly.getId(), tieLate.getId(), last.getId());
        assertThat(result).extracting("rank").containsExactly(1L, 2L, 2L, 3L);
        assertThat(result).extracting("mainImageUrl").first().isEqualTo("main-top");
    }

    @Test
    void filtersByCategoryAndAppliesTheLimit() {
        Event musicalA = event("musical a", "musical", LocalDate.of(2026, 12, 31));
        Event musicalB = event("musical b", "musical", LocalDate.of(2026, 12, 31));
        Event musicalC = event("musical c", "musical", LocalDate.of(2026, 12, 31));
        Event concert = event("concert", "concert", LocalDate.of(2026, 12, 31));
        paid(musicalA, 3, NOW.minusDays(1));
        paid(musicalB, 2, NOW.minusDays(1));
        paid(musicalC, 1, NOW.minusDays(1));
        paid(concert, 10, NOW.minusDays(1));

        assertThat(ranking("musical", 2)).extracting("id").containsExactly(musicalA.getId(), musicalB.getId());
        assertThat(ranking("concert", 5)).extracting("id").containsExactly(concert.getId());
    }

    @Test
    void returnsNothingWithoutRecentSales() {
        event("no sales");

        assertThat(ranking(null, 5)).isEmpty();
    }
}
