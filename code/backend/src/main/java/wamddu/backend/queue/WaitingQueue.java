package wamddu.backend.queue;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.Set;

/**
 * Per-event waiting queue in Redis (docs/design/waiting-queue.md).
 * Users wait in a sorted set ordered by an increasing sequence number; every second the first N get a pass
 * that allows ordering for a few minutes. Everything here touches Redis only, never the database, so that
 * thousands of waiting users asking for their position cannot take the server's capacity away from orders.
 * If Redis fails, callers let the user through: the ticket row lock still prevents overselling.
 */
@Slf4j
@Component
public class WaitingQueue {
    // Joins once: re-entering (refresh, double click) keeps the original place. Returns -1 if already admitted.
    private static final RedisScript<Long> ENTER = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[4]) == 1 then return -1 end
            if not redis.call('ZSCORE', KEYS[1], ARGV[1]) then
              redis.call('ZADD', KEYS[1], redis.call('INCR', KEYS[2]), ARGV[1])
              redis.call('SADD', KEYS[3], ARGV[2])
            end
            return redis.call('ZRANK', KEYS[1], ARGV[1])
            """, Long.class);

    // One admission per event per second across all servers: only the caller that creates the tick key admits.
    private static final RedisScript<Long> ADMIT = new DefaultRedisScript<>("""
            if not redis.call('SET', KEYS[2], '1', 'NX', 'EX', 5) then return -1 end
            local users = redis.call('ZPOPMIN', KEYS[1], ARGV[1])
            for i = 1, #users, 2 do
              redis.call('SET', ARGV[3] .. users[i], '1', 'EX', ARGV[2])
            end
            if redis.call('ZCARD', KEYS[1]) == 0 then redis.call('SREM', KEYS[3], ARGV[4]) end
            return #users / 2
            """, Long.class);

    static final String ACTIVE = "queue:active";

    private final StringRedisTemplate redis;
    private final int admitPerSecond;
    private final int passTtlSeconds;
    private final Clock clock;
    private final Counter admitted;
    private final Counter redisErrors;

    @Autowired
    public WaitingQueue(StringRedisTemplate redis, MeterRegistry meters,
                        @Value("${queue.admit-per-second:20}") int admitPerSecond,
                        @Value("${queue.pass-ttl-seconds:300}") int passTtlSeconds) {
        this(redis, meters, admitPerSecond, passTtlSeconds, Clock.systemUTC());
    }

    WaitingQueue(StringRedisTemplate redis, MeterRegistry meters, int admitPerSecond, int passTtlSeconds, Clock clock) {
        if (admitPerSecond < 1 || passTtlSeconds < 1) {
            throw new IllegalArgumentException("queue.admit-per-second and queue.pass-ttl-seconds must be positive");
        }
        this.redis = redis;
        this.admitPerSecond = admitPerSecond;
        this.passTtlSeconds = passTtlSeconds;
        this.clock = clock;
        this.admitted = Counter.builder("freeticket.queue.admitted").description("Users given an order pass").register(meters);
        this.redisErrors = Counter.builder("freeticket.queue.redis.errors")
                .description("Queue operations that failed in Redis and let the user through").register(meters);
    }

    public QueueStatus enter(long eventId, long userId) {
        try {
            Long rank = redis.execute(ENTER, List.of(waiting(eventId), seq(eventId), ACTIVE, pass(eventId, userId)),
                    String.valueOf(userId), String.valueOf(eventId));
            return rank == null || rank < 0 ? admittedStatus(eventId, userId) : waitingStatus(rank + 1);
        } catch (DataAccessException unavailable) {
            return bypass("enter", unavailable);
        }
    }

    /** Position for someone already in line; {@code null} when the user is neither waiting nor admitted. */
    public QueueStatus status(long eventId, long userId) {
        try {
            // Line first, pass second. Admission removes the user from the line and creates the pass in one script,
            // so a user missing from the line already has the pass. The other order could miss both when admission
            // ran between the two reads (seen as 0.8% "not in line" errors in the 2026-11-17 load test).
            Long rank = redis.opsForZSet().rank(waiting(eventId), String.valueOf(userId));
            if (rank != null) return waitingStatus(rank + 1);
            return Boolean.TRUE.equals(redis.hasKey(pass(eventId, userId))) ? admittedStatus(eventId, userId) : null;
        } catch (DataAccessException unavailable) {
            return bypass("status", unavailable);
        }
    }

    /** True when the user may order. Redis errors count as admitted (fail-open). */
    public boolean hasPass(long eventId, long userId) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(pass(eventId, userId)));
        } catch (DataAccessException unavailable) {
            bypass("pass check", unavailable);
            return true;
        }
    }

    /** Lets the next N users of every event with a line in. Safe to call often and from several servers. */
    public int admitAll() {
        long second = clock.millis() / 1000;
        int total = 0;
        try {
            Set<String> events = redis.opsForSet().members(ACTIVE);
            if (events == null) return 0;
            for (String eventId : events) {
                Long count = redis.execute(ADMIT, List.of("queue:" + eventId + ":waiting", "queue:" + eventId + ":tick:" + second, ACTIVE),
                        String.valueOf(admitPerSecond), String.valueOf(passTtlSeconds), "queue:" + eventId + ":pass:", eventId);
                if (count != null && count > 0) total += count;
            }
        } catch (DataAccessException unavailable) {
            redisErrors.increment();
            log.warn("Queue admission skipped: Redis unavailable ({})", unavailable.getClass().getSimpleName());
        }
        admitted.increment(total);
        return total;
    }

    /** Users waiting across all events, for the Grafana gauge. */
    public long waitingTotal() {
        try {
            Set<String> events = redis.opsForSet().members(ACTIVE);
            if (events == null) return 0;
            long sum = 0;
            for (String eventId : events) {
                Long size = redis.opsForZSet().zCard("queue:" + eventId + ":waiting");
                sum += size == null ? 0 : size;
            }
            return sum;
        } catch (DataAccessException unavailable) {
            return 0;
        }
    }

    private QueueStatus waitingStatus(long position) {
        long waitSeconds = (position + admitPerSecond - 1) / admitPerSecond;
        return QueueStatus.waiting(position, waitSeconds, nextPollSeconds(waitSeconds));
    }

    private QueueStatus admittedStatus(long eventId, long userId) {
        Long ttl = redis.getExpire(pass(eventId, userId));
        return QueueStatus.admitted(ttl == null || ttl < 0 ? passTtlSeconds : ttl);
    }

    private QueueStatus bypass(String operation, DataAccessException cause) {
        redisErrors.increment();
        log.warn("Queue {} failed, letting the user through: Redis unavailable ({})", operation, cause.getClass().getSimpleName());
        return QueueStatus.queueUnavailable();
    }

    /** Front of the line asks every 2s; the back waits up to 30s so polling stays small next to real orders. */
    static int nextPollSeconds(long waitSeconds) {
        return (int) Math.max(2, Math.min(30, (waitSeconds + 1) / 2));
    }

    static String waiting(long eventId) { return "queue:" + eventId + ":waiting"; }
    static String seq(long eventId) { return "queue:" + eventId + ":seq"; }
    static String pass(long eventId, long userId) { return "queue:" + eventId + ":pass:" + userId; }
}
