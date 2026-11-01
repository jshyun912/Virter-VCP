package jshyun912.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

@ExtendWith(MockitoExtension.class)
@DisplayName("HealthService")
class HealthServiceTest {
    private static final Instant INITIAL_TIME = Instant.parse("2026-11-01T00:00:00Z");
    private static final String BACKEND_HEALTH_URI = "http://localhost:8081/actuator/health";

    @Mock
    private Clock clock;

    private MockRestServiceServer backendServer;
    private HealthService healthService;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        backendServer = MockRestServiceServer.bindTo(builder).build();
        given(clock.getZone()).willReturn(ZoneOffset.UTC);
        given(clock.instant()).willReturn(INITIAL_TIME);
        healthService = new HealthService(builder.build(), clock, 8081);
    }

    @Nested
    @DisplayName("requireHealth를 호출하면")
    class RequireHealth {
        @Test
        @DisplayName("첫 요청에서 백엔드 상태를 점검한다")
        void checksBackendOnFirstRequest() {
            // given
            backendServer.expect(requestTo(BACKEND_HEALTH_URI)).andRespond(withStatus(HttpStatus.OK));

            // when
            HealthResponse response = healthService.requireHealth();

            // then
            assertEquals(new HealthResponse(true, 200), response);
            backendServer.verify();
        }

        @Test
        @DisplayName("마지막 점검 후 정확히 1분까지 저장된 결과를 재사용한다")
        void reusesResultThroughOneMinute() {
            // given
            backendServer.expect(requestTo(BACKEND_HEALTH_URI)).andRespond(withStatus(HttpStatus.OK));
            healthService.requireHealth();

            // when
            given(clock.instant()).willReturn(INITIAL_TIME.plusSeconds(59));
            HealthResponse beforeExpiry = healthService.requireHealth();
            given(clock.instant()).willReturn(INITIAL_TIME.plusSeconds(60));
            HealthResponse atExpiry = healthService.requireHealth();

            // then
            assertEquals(new HealthResponse(true, 200), beforeExpiry);
            assertEquals(new HealthResponse(true, 200), atExpiry);
            backendServer.verify();
        }

        @Test
        @DisplayName("시계가 뒤로 조정되면 즉시 갱신하고 새 점검 시각을 기준으로 캐시한다")
        void refreshesWhenClockMovesBackwards() {
            // given
            backendServer.expect(requestTo(BACKEND_HEALTH_URI)).andRespond(withStatus(HttpStatus.OK));
            backendServer.expect(requestTo(BACKEND_HEALTH_URI))
                    .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
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
            backendServer.verify();
        }

        @Test
        @DisplayName("실패 결과를 캐시하고 만료 후 정상 복구를 반영한다")
        void refreshesAfterOneMinuteAndRecoversFromFailure() {
            // given
            backendServer.expect(requestTo(BACKEND_HEALTH_URI)).andRespond(withStatus(HttpStatus.OK));
            backendServer.expect(requestTo(BACKEND_HEALTH_URI))
                    .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
            backendServer.expect(requestTo(BACKEND_HEALTH_URI)).andRespond(withStatus(HttpStatus.OK));
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
            backendServer.verify();
        }

        @Test
        @DisplayName("백엔드 연결 타임아웃을 비정상 상태로 저장한다")
        void cachesBackendConnectionFailures() {
            // given
            backendServer.expect(requestTo(BACKEND_HEALTH_URI))
                    .andRespond(withException(new SocketTimeoutException("timed out")));

            // when
            HealthResponse response = healthService.requireHealth();
            HealthResponse cached = healthService.requireHealth();

            // then
            assertEquals(new HealthResponse(false, 503), response);
            assertEquals(response, cached);
            backendServer.verify();
        }

        @Test
        @DisplayName("갱신 중인 다른 요청을 기다리지 않고 이전 결과를 반환한다")
        void returnsPreviousResultWhileAnotherRequestRefreshes() throws Exception {
            // given
            CountDownLatch refreshStarted = new CountDownLatch(1);
            CountDownLatch allowRefresh = new CountDownLatch(1);
            backendServer.expect(requestTo(BACKEND_HEALTH_URI)).andRespond(withStatus(HttpStatus.OK));
            backendServer.expect(requestTo(BACKEND_HEALTH_URI))
                    .andRespond(delayedResponse(HttpStatus.SERVICE_UNAVAILABLE, refreshStarted, allowRefresh));
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
                backendServer.verify();
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
            backendServer.expect(requestTo(BACKEND_HEALTH_URI))
                    .andRespond(delayedResponse(HttpStatus.OK, refreshStarted, allowRefresh));

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
                backendServer.verify();
            } finally {
                allowRefresh.countDown();
                executor.shutdownNow();
            }
        }
    }

    private ResponseCreator delayedResponse(HttpStatus status, CountDownLatch started, CountDownLatch released) {
        return request -> {
            started.countDown();
            try {
                assertTrue(released.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException(exception);
            }
            return withStatus(status).createResponse(request);
        };
    }
}
