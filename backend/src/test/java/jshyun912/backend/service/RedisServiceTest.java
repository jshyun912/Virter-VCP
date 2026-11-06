package jshyun912.backend.service;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@DisplayName("RedisService 실제 Redis 통합 테스트")
class RedisServiceTest {
    private static GenericContainer<?> redis;
    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static RedisService redisService;
    private static RedisService anotherRedisService;

    private String key;
    private String lockKey;

    @BeforeAll
    static void startRedis() {
        String host = System.getenv("REDIS_TEST_HOST");
        String port = System.getenv("REDIS_TEST_PORT");
        if (host == null && port == null) {
            redis = new GenericContainer<>(DockerImageName.parse("redis:8.10.3-alpine"))
                    .withExposedPorts(6379);
            redis.start();
            host = redis.getHost();
            port = redis.getMappedPort(6379).toString();
        } else if (host == null || port == null) {
            throw new IllegalArgumentException("REDIS_TEST_HOST와 REDIS_TEST_PORT는 함께 지정해야 합니다.");
        }

        var clientConfiguration = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(2))
                .shutdownTimeout(Duration.ofMillis(100))
                .build();
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(host, Integer.parseInt(port)), clientConfiguration);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisService = new RedisService(redisTemplate);
        anotherRedisService = new RedisService(redisTemplate);
    }

    @AfterAll
    static void stopRedis() {
        try {
            if (connectionFactory != null) {
                connectionFactory.destroy();
            }
        } finally {
            if (redis != null) {
                redis.stop();
            }
        }
    }

    @BeforeEach
    void createIsolatedKeys() {
        key = "redis-service-test:" + UUID.randomUUID();
        lockKey = "lock:codeforces:" + key;
    }

    @AfterEach
    void deleteOnlyTestKeys() {
        redisTemplate.delete(Set.of(key, lockKey, lockKey + ":other"));
    }

    @Test
    @DisplayName("없는 키는 빈 집합으로 반환하고 캐시가 없는 것으로 처리한다")
    void returnsCacheMissForMissingKey() {
        // when
        Set<Integer> result = redisService.get(key);
        boolean exists = redisService.hasKey(key);

        // then
        assertEquals(Set.of(), result);
        assertFalse(exists);
    }

    @Nested
    @DisplayName("대회 기록을 저장하면")
    class GivenSavedContests {
        @BeforeEach
        void saveContests() {
            redisService.add(key, Set.of(100, 200));
        }

        @Test
        @DisplayName("0 표식을 항상 함께 저장하고 정수 집합으로 조회한다")
        void storesMarkerAndReadsIntegerIds() {
            // when
            Set<Integer> result = redisService.get(key);

            // then
            assertEquals(Set.of("0", "100", "200"), redisTemplate.opsForSet().members(key));
            assertEquals(Set.of(0, 100, 200), result);
            assertTrue(redisService.hasKey(key));
        }

        @Test
        @DisplayName("첫 저장 시점부터 2시간의 만료 시간을 설정한다")
        void setsTwoHourExpiry() {
            // when
            Long remainingMillis = redisTemplate.getExpire(key, TimeUnit.MILLISECONDS);

            // then
            assertNotNull(remainingMillis);
            assertTrue(remainingMillis > 7_190_000 && remainingMillis <= 7_200_000);
        }

        @Nested
        @DisplayName("아직 유효한 캐시에 다시 저장하면")
        class GivenLiveCache {
            @BeforeEach
            void shortenExpiryWithoutExpiring() {
                redisTemplate.expire(key, Duration.ofSeconds(30));
            }

            @Test
            @DisplayName("기존 기록을 교체하거나 새 기록을 누적하지 않는다")
            void preservesExistingRecords() {
                // when
                redisService.add(key, Set.of(300));

                // then
                assertEquals(Set.of(0, 100, 200), redisService.get(key));
            }

            @Test
            @DisplayName("기존 만료 시간을 연장하지 않는다")
            void doesNotRenewExpiry() {
                // given
                Long before = redisTemplate.getExpire(key, TimeUnit.MILLISECONDS);

                // when
                redisService.add(key, Set.of(300));
                Long after = redisTemplate.getExpire(key, TimeUnit.MILLISECONDS);

                // then
                assertNotNull(before);
                assertNotNull(after);
                assertTrue(after > 0 && after <= before);
            }
        }

        @Nested
        @DisplayName("캐시가 만료되면")
        class GivenExpiredCache {
            @BeforeEach
            void expireCache() {
                redisTemplate.expire(key, Duration.ofMillis(50));
                await().pollInterval(Duration.ofMillis(10)).atMost(Duration.ofSeconds(2))
                        .until(() -> Boolean.FALSE.equals(redisTemplate.hasKey(key)));
            }

            @Test
            @DisplayName("빈 집합을 반환하고 캐시가 없는 것으로 처리한다")
            void returnsCacheMiss() {
                // when
                Set<Integer> result = redisService.get(key);

                // then
                assertEquals(Set.of(), result);
                assertFalse(redisService.hasKey(key));
            }

            @Test
            @DisplayName("새 기록과 새로운 2시간 만료 시간을 저장할 수 있다")
            void savesFreshRecords() {
                // when
                redisService.add(key, Set.of(300));

                // then
                assertEquals(Set.of(0, 300), redisService.get(key));
                assertTrue(redisTemplate.getExpire(key) > 7190);
            }
        }
    }

    @Test
    @DisplayName("빈 제출 기록도 0 표식으로 저장해 유효한 빈 사용자 캐시로 구분한다")
    void cachesEmptySubmissionHistory() {
        // when
        redisService.add(key, Set.of());

        // then
        assertEquals(Set.of("0"), redisTemplate.opsForSet().members(key));
        assertEquals(Set.of(0), redisService.get(key));
        assertTrue(redisService.hasKey(key));
        assertTrue(redisTemplate.getExpire(key) > 0);
    }

    @Nested
    @DisplayName("만료 시간이 없는 기록이 있으면")
    class GivenPersistentRecord {
        @BeforeEach
        void createUntrustedRecord() {
            redisTemplate.opsForSet().add(key, "0", "999");
        }

        @Test
        @DisplayName("기록이 존재해도 신뢰하지 않고 캐시 미스로 처리한다")
        void rejectsUntrustedRecords() {
            // when
            Set<Integer> result = redisService.get(key);

            // then
            assertEquals(Set.of(), result);
            assertFalse(redisService.hasKey(key));
        }

        @Test
        @DisplayName("다시 저장할 때 신뢰할 수 없는 기록을 제거하고 만료 시간을 설정한다")
        void replacesUntrustedRecords() {
            // when
            redisService.add(key, Set.of(100));

            // then
            assertEquals(Set.of("0", "100"), redisTemplate.opsForSet().members(key));
            assertEquals(Set.of(0, 100), redisService.get(key));
            assertTrue(redisTemplate.getExpire(key) > 7190);
        }
    }

    @Test
    @DisplayName("동시 저장에서는 먼저 저장된 한 결과만 유지하고 서로 누적하지 않는다")
    void keepsOnlyFirstConcurrentWrite() throws Exception {
        // given
        CyclicBarrier start = new CyclicBarrier(2);

        // when
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await(2, TimeUnit.SECONDS);
                redisService.add(key, Set.of(100));
                return null;
            });
            var second = executor.submit(() -> {
                start.await(2, TimeUnit.SECONDS);
                anotherRedisService.add(key, Set.of(200));
                return null;
            });
            first.get(3, TimeUnit.SECONDS);
            second.get(3, TimeUnit.SECONDS);
        }

        // then
        assertTrue(Set.of(Set.of(0, 100), Set.of(0, 200)).contains(redisService.get(key)));
        assertTrue(redisTemplate.getExpire(key) > 0);
    }

    @Nested
    @DisplayName("같은 키의 락을 한 요청이 보유하면")
    class GivenHeldLock {
        private String ownerToken;

        @BeforeEach
        void acquireLock() {
            ownerToken = redisService.lock(key);
        }

        @Test
        @DisplayName("비정상 종료에도 영구히 남지 않도록 락에 만료 시간을 설정한다")
        void givesLockAnExpiry() {
            // when
            Long remainingMillis = redisTemplate.getExpire(lockKey, TimeUnit.MILLISECONDS);

            // then
            assertNotNull(remainingMillis);
            assertTrue(remainingMillis > 0 && remainingMillis <= 60_000);
        }

        @Test
        @DisplayName("소유자가 아닌 토큰으로 락을 해제할 수 없다")
        void rejectsUnlockFromAnotherOwner() {
            // when
            anotherRedisService.unlock(key, "not-the-owner");

            // then
            assertEquals(ownerToken, redisTemplate.opsForValue().get(lockKey));
        }

        @Test
        @DisplayName("소유자가 해제하면 다음 요청이 새 토큰으로 락을 획득할 수 있다")
        void allowsNextOwnerAfterRelease() {
            // when
            redisService.unlock(key, ownerToken);
            String nextToken = anotherRedisService.lock(key);

            // then
            assertNotEquals(ownerToken, nextToken);
            assertEquals(nextToken, redisTemplate.opsForValue().get(lockKey));
        }

        @Test
        @DisplayName("같은 키의 요청은 기존 소유자가 해제할 때까지 대기한다")
        void serializesRequestsForSameKey() throws Exception {
            // given
            CountDownLatch started = new CountDownLatch(1);

            // when
            try (var executor = Executors.newSingleThreadExecutor()) {
                var waiting = executor.submit(() -> {
                    started.countDown();
                    return anotherRedisService.lock(key);
                });
                try {
                    assertTrue(started.await(2, TimeUnit.SECONDS));
                    assertThrows(TimeoutException.class, () -> waiting.get(150, TimeUnit.MILLISECONDS));
                    redisService.unlock(key, ownerToken);
                    String nextToken = waiting.get(3, TimeUnit.SECONDS);

                    // then
                    assertNotEquals(ownerToken, nextToken);
                    assertEquals(nextToken, redisTemplate.opsForValue().get(lockKey));
                } finally {
                    redisService.unlock(key, ownerToken);
                    waiting.cancel(true);
                }
            }
        }

        @Test
        @DisplayName("다른 키의 락은 기존 락을 기다리지 않고 획득한다")
        void allowsIndependentKeys() {
            // when
            String token = assertTimeoutPreemptively(Duration.ofSeconds(2),
                    () -> anotherRedisService.lock(key + ":other"));

            // then
            assertEquals(token, redisTemplate.opsForValue().get(lockKey + ":other"));
            assertEquals(ownerToken, redisTemplate.opsForValue().get(lockKey));
        }

        @Test
        @DisplayName("락 만료 후 새 소유자가 획득한 락을 이전 소유자가 삭제할 수 없다")
        void protectsNewOwnerAfterExpiry() {
            // given
            redisTemplate.expire(lockKey, Duration.ofMillis(50));
            await().pollInterval(Duration.ofMillis(10)).atMost(Duration.ofSeconds(2))
                    .until(() -> Boolean.FALSE.equals(redisTemplate.hasKey(lockKey)));

            // when
            String nextToken = anotherRedisService.lock(key);
            redisService.unlock(key, ownerToken);

            // then
            assertNotEquals(ownerToken, nextToken);
            assertEquals(nextToken, redisTemplate.opsForValue().get(lockKey));
        }

        @Test
        @DisplayName("대기 중 인터럽트가 발생하면 중단하고 기존 소유자의 락을 유지한다")
        void preservesInterruptAndOriginalLock() throws Exception {
            // given
            CountDownLatch started = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicBoolean interrupted = new AtomicBoolean();
            Thread waiting = new Thread(() -> {
                started.countDown();
                try {
                    anotherRedisService.lock(key);
                } catch (Throwable exception) {
                    failure.set(exception);
                    interrupted.set(Thread.currentThread().isInterrupted());
                }
            });

            // when
            waiting.start();
            try {
                assertTrue(started.await(2, TimeUnit.SECONDS));
                waiting.interrupt();
                waiting.join(3000);

                // then
                assertFalse(waiting.isAlive());
                assertTrue(failure.get() instanceof DataAccessException);
                assertTrue(interrupted.get());
                assertEquals(ownerToken, redisTemplate.opsForValue().get(lockKey));
            } finally {
                waiting.interrupt();
                waiting.join(3000);
            }
        }

        @Test
        @Timeout(50)
        @DisplayName("락 획득을 40초간 기다려도 해제되지 않으면 실패하고 기존 락을 유지한다")
        void failsWhenLockWaitExpires() {
            // given
            long startedAt = System.nanoTime();

            // when
            assertThrows(CannotAcquireLockException.class, () -> anotherRedisService.lock(key));

            // then
            assertTrue(Duration.ofNanos(System.nanoTime() - startedAt).compareTo(Duration.ofSeconds(39)) >= 0);
            assertEquals(ownerToken, redisTemplate.opsForValue().get(lockKey));
        }
    }
}
