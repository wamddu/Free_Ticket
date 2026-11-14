package wamddu.backend.payment.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import wamddu.backend.order.domain.OrderStatus;
import wamddu.backend.order.repository.OrderRepository;
import wamddu.backend.payment.repository.PaymentReconciliationIssueRepository;

import java.util.List;

/**
 * Payment states that need attention, read from the database on each Prometheus scrape (every 15s).
 * A rising "unfinished" count means Toss or the DB is failing and the recovery scheduler is retrying;
 * any "review required" or open reconciliation issue needs a person.
 */
@Component
@RequiredArgsConstructor
public class PaymentMetrics implements MeterBinder {
    private final OrderRepository orderRepository;
    private final PaymentReconciliationIssueRepository issueRepository;

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("freeticket.payments.unfinished", orderRepository,
                        repository -> repository.countByStatusAndRecoveryReviewRequiredFalse(OrderStatus.CONFIRMING))
                .description("Orders waiting for the recovery scheduler")
                .tag("state", "confirming")
                .register(registry);
        Gauge.builder("freeticket.payments.unfinished", orderRepository,
                        repository -> repository.countByStatusAndRecoveryReviewRequiredFalse(OrderStatus.CANCELING))
                .description("Orders waiting for the recovery scheduler")
                .tag("state", "canceling")
                .register(registry);
        Gauge.builder("freeticket.payments.review.required", orderRepository,
                        repository -> repository.countByStatusInAndRecoveryReviewRequiredTrue(
                                List.of(OrderStatus.CONFIRMING, OrderStatus.CANCELING)))
                .description("Orders the recovery scheduler gave up on; an admin must check them")
                .register(registry);
        Gauge.builder("freeticket.payments.reconciliation.open", issueRepository,
                        PaymentReconciliationIssueRepository::countByResolvedAtUtcIsNull)
                .description("PG cancellations that do not match the database and are not resolved yet")
                .register(registry);
    }
}
