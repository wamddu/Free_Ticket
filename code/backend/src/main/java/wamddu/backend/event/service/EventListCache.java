package wamddu.backend.event.service;

import org.springframework.beans.factory.annotation.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import wamddu.backend.global.response.ApiResponse;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

@Component
@Slf4j
public class EventListCache {
    static final String KEY = "free-ticket:events:pages:v2";
    private static final byte[] KEY_BYTES = KEY.getBytes(StandardCharsets.UTF_8);
    // Keep all pages within the original TTL; filling another page must not extend older entries.
    private static final byte[] STORE_PAGE = """
            redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
            if redis.call('TTL', KEYS[1]) == -1 then redis.call('EXPIRE', KEYS[1], 30) end
            return 1
            """.getBytes(StandardCharsets.UTF_8);
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final boolean enabled;
    // 동시에 읽는 Redis 응답 수를 제한해 메모리 사용을 묶어 둔다. 규모가 커지면 측정한 페이지 크기에 맞춰 조정한다.
    private final Semaphore reads = new Semaphore(32, true);

    public EventListCache(StringRedisTemplate redis, ObjectMapper json,
                          @Value("${event-list-cache.enabled:false}") boolean enabled) {
        this.redis = redis;
        this.json = json;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public byte[] getOrLoad(String field, Supplier<? extends ApiResponse<?>> loader) {
        if (!enabled) return json.writeValueAsBytes(loader.get());
        byte[] fieldBytes = field.getBytes(StandardCharsets.UTF_8);
        try {
            byte[] cached = read(fieldBytes);
            if (cached != null) return cached;
        } catch (DataAccessException unavailable) {
            log.warn("Event list cache unavailable ({}); reading from database", unavailable.getClass().getSimpleName());
            return json.writeValueAsBytes(loader.get());
        }
        // 캐시 미스 시 DB 조회를 한 번만 하도록 이 서버 안에서만 막는다. 서버를 여러 대로 늘리면 분산 락이 필요하다.
        synchronized (this) {
            try {
                byte[] cached = read(fieldBytes);
                if (cached != null) return cached;
            } catch (DataAccessException unavailable) {
                log.warn("Event list cache read failed; reading from database");
                return json.writeValueAsBytes(loader.get());
            }
            byte[] loaded = json.writeValueAsBytes(loader.get());
            try {
                redis.execute((RedisCallback<Long>) connection -> connection.scriptingCommands()
                        .eval(STORE_PAGE, ReturnType.INTEGER, 1, KEY_BYTES, fieldBytes, loaded));
            } catch (DataAccessException unavailable) {
                log.warn("Event list cache write failed; returning database response");
            }
            return loaded;
        }
    }

    private byte[] read(byte[] fieldBytes) {
        reads.acquireUninterruptibly();
        try {
            return redis.execute((RedisCallback<byte[]>) connection -> connection.hashCommands().hGet(KEY_BYTES, fieldBytes));
        } finally {
            reads.release();
        }
    }

    public void invalidateAfterCommit() {
        if (!enabled) return;
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evict();
                }
            });
        } else {
            evict();
        }
    }

    private synchronized void evict() {
        try {
            redis.delete(KEY);
        } catch (DataAccessException unavailable) {
            log.warn("Event list cache invalidation failed; entry expires within 30 seconds");
        }
    }
}
