package wamddu.backend.queue;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Admits the next users four times a second; WaitingQueue lets only one call per second per event through.
 * Runs on its own thread: the shared @Scheduled pool (2 threads) also runs payment recovery and reconciliation,
 * which can take long enough to stall admissions.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "queue.admission.enabled", havingValue = "true", matchIfMissing = true)
public class QueueAdmissionScheduler {
    private final WaitingQueue queue;
    private ScheduledExecutorService executor;

    public QueueAdmissionScheduler(WaitingQueue queue, MeterRegistry meters) {
        this.queue = queue;
        Gauge.builder("freeticket.queue.waiting", queue, WaitingQueue::waitingTotal)
                .description("Users waiting in all event queues").register(meters);
    }

    @PostConstruct
    void start() {
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "queue-admission");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleAtFixedRate(this::admit, 1000, 250, TimeUnit.MILLISECONDS);
    }

    void admit() {
        try {
            queue.admitAll();
        } catch (RuntimeException unexpected) {
            // An exception would cancel the fixed-rate task; keep admitting.
            log.error("Queue admission failed", unexpected);
        }
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }
}
