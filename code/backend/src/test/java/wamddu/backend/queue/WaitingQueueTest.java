package wamddu.backend.queue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class WaitingQueueTest {
    @ParameterizedTest(name = "wait {0}s -> ask again in {1}s")
    @CsvSource({"0, 2", "1, 2", "4, 2", "6, 3", "20, 10", "59, 30", "60, 30", "3600, 30"})
    void pollingSlowsDownTowardTheBackOfTheLine(long waitSeconds, int expected) {
        assertThat(WaitingQueue.nextPollSeconds(waitSeconds)).isEqualTo(expected);
    }
}
