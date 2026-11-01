package jshyun912.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import jshyun912.backend.dto.health.HealthResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroup;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.actuate.endpoint.StatusAggregator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.registry.DefaultHealthContributorRegistry;

@ExtendWith(MockitoExtension.class)
@DisplayName("HealthService")
class HealthServiceTest {
    private static final Instant INITIAL_TIME = Instant.parse("2026-11-01T00:00:00Z");

    @Mock
    private Clock clock;

    @Mock
    private HealthIndicator backendHealthIndicator;

    private HealthService healthService;

    @BeforeEach
    void setUp() {
        var registry = new DefaultHealthContributorRegistry();
        registry.registerContributor("backend", backendHealthIndicator);
        HealthEndpointGroup group = mock(HealthEndpointGroup.class);
        given(group.isMember("backend")).willReturn(true);
        given(group.getStatusAggregator()).willReturn(StatusAggregator.getDefault());
        HealthEndpoint healthEndpoint = new HealthEndpoint(
                registry, null, HealthEndpointGroups.of(group, Map.of()), null);
        given(clock.getZone()).willReturn(ZoneOffset.UTC);
        given(clock.instant()).willReturn(INITIAL_TIME);
        healthService = new HealthService(healthEndpoint, clock);
    }

    @Nested
    @DisplayName("requireHealth를 호출하면")
    class RequireHealth {
        @Test
        @DisplayName("첫 요청에서 백엔드 상태를 점검한다")
        void checksBackendOnFirstRequest() {
            // given
            given(backendHealthIndicator.health(true)).willReturn(Health.up().build());

            // when
            HealthResponse response = healthService.requireHealth();

            // then
            assertEquals(new HealthResponse(true, 200), response);
            then(backendHealthIndicator).should().health(true);
        }

        @Test
        @DisplayName("마지막 점검 후 정확히 1분까지 저장된 결과를 재사용한다")
        void reusesResultThroughOneMinute() {
            // given
            given(backendHealthIndicator.health(true)).willReturn(Health.up().build());
            healthService.requireHealth();

            // when
            given(clock.instant()).willReturn(INITIAL_TIME.plusSeconds(59));
            HealthResponse beforeExpiry = healthService.requireHealth();
            given(clock.instant()).willReturn(INITIAL_TIME.plusSeconds(60));
            HealthResponse atExpiry = healthService.requireHealth();

            // then
            assertEquals(new HealthResponse(true, 200), beforeExpiry);
            assertEquals(new HealthResponse(true, 200), atExpiry);
            then(backendHealthIndicator).should().health(true);
        }

        @Test
        @DisplayName("시계가 뒤로 조정되면 즉시 갱신하고 새 점검 시각을 기준으로 캐시한다")
        void refreshesWhenClockMovesBackwards() {
            // given
            given(backendHealthIndicator.health(true)).willReturn(Health.up().build(), Health.down().build());
            healthService.requireHealth();
            Instant rolledBackTime = INITIAL_TIME.minusSeconds(3600);

            // when
            given(clock.instant()).willReturn(rolledBackTime);
            HealthResponse refreshed = healthService.requireHealth();
            given(clock.instant()).willReturn(rolledBackTime.plusSeconds(1));
            HealthResponse cached = healthService.requireHealth();

            // then
            assertEquals(new HealthResponse(false, 503), refreshed);
            assertEquals(new HealthResponse(false, 503), cached);
            then(backendHealthIndicator).should(times(2)).health(true);
        }

        @Test
        @DisplayName("실패 결과를 캐시하고 만료 후 정상 복구를 반영한다")
        void refreshesAfterOneMinuteAndRecoversFromFailure() {
            // given
            given(backendHealthIndicator.health(true))
                    .willReturn(Health.up().build(), Health.down().build(), Health.up().build());
            healthService.requireHealth();

            // when
            given(clock.instant()).willReturn(INITIAL_TIME.plusSeconds(61));
            HealthResponse refreshed = healthService.requireHealth();
            given(clock.instant()).willReturn(INITIAL_TIME.plusSeconds(120));
            HealthResponse cachedFailure = healthService.requireHealth();
            given(clock.instant()).willReturn(INITIAL_TIME.plusSeconds(122));
            HealthResponse recovered = healthService.requireHealth();

            // then
            assertEquals(new HealthResponse(false, 503), refreshed);
            assertEquals(new HealthResponse(false, 503), cachedFailure);
            assertEquals(new HealthResponse(true, 200), recovered);
            then(backendHealthIndicator).should(times(3)).health(true);
        }

        @Test
        @DisplayName("Actuator의 비정상 상태를 저장하고 재사용한다")
        void cachesUnhealthyActuatorStatus() {
            // given
            given(backendHealthIndicator.health(true)).willReturn(Health.down().build());

            // when
            HealthResponse response = healthService.requireHealth();
            HealthResponse cached = healthService.requireHealth();

            // then
            assertEquals(new HealthResponse(false, 503), response);
            assertEquals(response, cached);
            then(backendHealthIndicator).should().health(true);
        }

        @Test
        @DisplayName("갱신 중인 다른 요청을 기다리지 않고 이전 결과를 반환한다")
        void returnsPreviousResultWhileAnotherRequestRefreshes() throws Exception {
            // given
            CountDownLatch refreshStarted = new CountDownLatch(1);
            CountDownLatch allowRefresh = new CountDownLatch(1);
            given(backendHealthIndicator.health(true))
                    .willReturn(Health.up().build())
                    .willAnswer(delayedHealth(Health.down().build(), refreshStarted, allowRefresh));
            healthService.requireHealth();
            given(clock.instant()).willReturn(INITIAL_TIME.plusSeconds(61));

            // when
            var executor = Executors.newFixedThreadPool(2);
            try {
                var refreshing = executor.submit(healthService::requireHealth);
                assertTrue(refreshStarted.await(2, TimeUnit.SECONDS));
                HealthResponse cached = executor.submit(healthService::requireHealth).get(1, TimeUnit.SECONDS);
                allowRefresh.countDown();
                HealthResponse refreshed = refreshing.get(2, TimeUnit.SECONDS);

                // then
                assertEquals(new HealthResponse(true, 200), cached);
                assertEquals(new HealthResponse(false, 503), refreshed);
                then(backendHealthIndicator).should(times(2)).health(true);
            } finally {
                allowRefresh.countDown();
                executor.shutdownNow();
            }
        }

        @Test
        @DisplayName("최초 점검 중인 다른 요청에는 미확인 상태 503을 반환한다")
        void returnsUnavailableWhileInitialCheckIsRunning() throws Exception {
            // given
            CountDownLatch refreshStarted = new CountDownLatch(1);
            CountDownLatch allowRefresh = new CountDownLatch(1);
            given(backendHealthIndicator.health(true))
                    .willAnswer(delayedHealth(Health.up().build(), refreshStarted, allowRefresh));

            // when
            var executor = Executors.newFixedThreadPool(2);
            try {
                var refreshing = executor.submit(healthService::requireHealth);
                assertTrue(refreshStarted.await(2, TimeUnit.SECONDS));
                HealthResponse initial = executor.submit(healthService::requireHealth).get(1, TimeUnit.SECONDS);
                allowRefresh.countDown();
                HealthResponse refreshed = refreshing.get(2, TimeUnit.SECONDS);

                // then
                assertEquals(new HealthResponse(false, 503), initial);
                assertEquals(new HealthResponse(true, 200), refreshed);
                then(backendHealthIndicator).should().health(true);
            } finally {
                allowRefresh.countDown();
                executor.shutdownNow();
            }
        }
    }

    private Answer<Health> delayedHealth(Health health, CountDownLatch started, CountDownLatch released) {
        return invocation -> {
            started.countDown();
            try {
                assertTrue(released.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            return health;
        };
    }
}
