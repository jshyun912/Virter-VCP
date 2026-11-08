package jshyun912.backend.service;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RedisService {
    private static final Duration CACHE_TTL = Duration.ofHours(2);
    private static final String LOCK_PREFIX = "lock:codeforces:";
    public static final Duration LOCK_TTL = Duration.ofSeconds(60);
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            end
            return 0
            """, Long.class);
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> READ_CACHE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('pttl', KEYS[1]) < 0 then
                return {}
            end
            return redis.call('smembers', KEYS[1])
            """, List.class);
    private static final DefaultRedisScript<Long> WRITE_CACHE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('pttl', KEYS[1]) >= 0 then
                return 0
            end
            redis.call('del', KEYS[1])
            for i = 2, #ARGV do
                redis.call('sadd', KEYS[1], ARGV[i])
            end
            redis.call('pexpire', KEYS[1], ARGV[1])
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public String tryLock(String key) {
        String token = UUID.randomUUID().toString();
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(LOCK_PREFIX + key, token, LOCK_TTL))
                ? token : null;
    }

    public void unlock(String key, String token) {
        redisTemplate.execute(UNLOCK_SCRIPT, List.of(LOCK_PREFIX + key), token);
    }

    public void add(String key, Set<Integer> values) {
        Object[] arguments = Stream.concat(
                Stream.of(Long.toString(CACHE_TTL.toMillis()), "0"),
                values.stream().map(String::valueOf)
        ).toArray();
        redisTemplate.execute(WRITE_CACHE_SCRIPT, List.of(key), arguments);
    }

    public Boolean hasKey(String key) {
        return !get(key).isEmpty();
    }

    public Set<Integer> get(String key) {
        List<?> members = redisTemplate.execute(READ_CACHE_SCRIPT, List.of(key));
        if (members == null) {
            return Set.of();
        }
        return members.stream()
                .map(Object::toString)
                .map(Integer::valueOf)
                .collect(Collectors.toSet());
    }
}
