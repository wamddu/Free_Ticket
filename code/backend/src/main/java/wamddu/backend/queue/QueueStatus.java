package wamddu.backend.queue;

/**
 * What the waiting page shows.
 * admitted: may order now (until passExpiresInSeconds). bypassed: the queue is unavailable, go ahead.
 * Otherwise position (1 = next), estimated wait and when to ask again.
 */
public record QueueStatus(
        boolean admitted,
        boolean bypassed,
        Long position,
        Long estimatedWaitSeconds,
        Integer nextPollSeconds,
        Long passExpiresInSeconds
) {
    static QueueStatus waiting(long position, long waitSeconds, int nextPollSeconds) {
        return new QueueStatus(false, false, position, waitSeconds, nextPollSeconds, null);
    }

    static QueueStatus admitted(long passExpiresInSeconds) {
        return new QueueStatus(true, false, null, null, null, passExpiresInSeconds);
    }

    /** The event has no queue: order directly. */
    static QueueStatus notRequired() {
        return new QueueStatus(true, false, null, null, null, null);
    }

    static QueueStatus queueUnavailable() {
        return new QueueStatus(true, true, null, null, null, null);
    }
}
