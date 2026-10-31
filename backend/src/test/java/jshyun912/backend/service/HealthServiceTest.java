package jshyun912.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import jshyun912.backend.dto.health.HealthResponse;
import jshyun912.backend.service.codeforces.CodeforcesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@ExtendWith(MockitoExtension.class)
@DisplayName("HealthService")
class HealthServiceTest {
    private static final Instant INITIAL_TIME = Instant.parse("2026-11-01T00:00:00Z");
    private static final String BACKEND_HEALTH_URI = "http://localhost:8081/actuator/health";

    @Mock
    private CodeforcesService codeforcesService;

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
        healthService = new HealthService(builder.build(), codeforcesService, clock);
    }

    @Nested
    @DisplayName("requireHealth를 호출하면")
    class RequireHealth {
        @Test
        @DisplayName("첫 요청에서 두 서비스 상태를 점검한다")
        void checksBothServicesOnFirstRequest() {
            // given
            given(codeforcesService.requireCodeforcesHealth()).willReturn(HttpStatus.OK);
            backendServer.expect(requestTo(BACKEND_HEALTH_URI)).andRespond(withStatus(HttpStatus.OK));

            // when
            HealthResponse response = healthService.requireHealth();

            // then
            assertEquals(new HealthResponse(true, 200, 200), response);
            backendServer.verify();
            then(codeforcesService).should().requireCodeforcesHealth();
        }

        @Test
        @DisplayName("마지막 점검 후 정확히 1분까지 저장된 결과를 재사용한다")
        void reusesResultThroughOneMinute() {
            // given
            given(codeforcesService.requireCodeforcesHealth()).willReturn(HttpStatus.OK);
            backendServer.expect(requestTo(BACKEND_HEALTH_URI)).andRespond(withStatus(HttpStatus.OK));
            healthService.requireHealth();

            // when
            given(clock.instant()).willReturn(INITIAL_TIME.plusSeconds(59));
            HealthResponse beforeExpiry = healthService.requireHealth();
            given(clock.instant()).willReturn(INITIAL_TIME.plusSeconds(60));
            HealthResponse atExpiry = healthService.requireHealth();

            // then
            assertEquals(new HealthResponse(true, 200, 200), beforeExpiry);
            assertEquals(new HealthResponse(true, 200, 200), atExpiry);
            backendServer.verify();
            then(codeforcesService).should().requireCodeforcesHealth();
        }

        @Test
        @DisplayName("1분이 지나면 두 상태를 갱신하고 실패 결과도 재사용한다")
        void refreshesAfterOneMinuteAndCachesFailures() {
            // given
            given(codeforcesService.requireCodeforcesHealth())
                    .willReturn(HttpStatus.OK, HttpStatus.SERVICE_UNAVAILABLE);
            backendServer.expect(requestTo(BACKEND_HEALTH_URI)).andRespond(withStatus(HttpStatus.OK));
            backendServer.expect(requestTo(BACKEND_HEALTH_URI))
                    .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
            healthService.requireHealth();

            // when
            given(clock.instant()).willReturn(INITIAL_TIME.plusSeconds(61));
            HealthResponse refreshed = healthService.requireHealth();
            given(clock.instant()).willReturn(INITIAL_TIME.plusSeconds(120));
            HealthResponse cachedFailure = healthService.requireHealth();

            // then
            assertEquals(new HealthResponse(false, 503, 503), refreshed);
            assertEquals(new HealthResponse(false, 503, 503), cachedFailure);
            backendServer.verify();
            then(codeforcesService).should(times(2)).requireCodeforcesHealth();
        }

        @Test
        @DisplayName("백엔드 연결 타임아웃을 비정상 상태로 저장한다")
        void cachesBackendConnectionFailures() {
            // given
            given(codeforcesService.requireCodeforcesHealth()).willReturn(HttpStatus.OK);
            backendServer.expect(requestTo(BACKEND_HEALTH_URI))
                    .andRespond(withException(new SocketTimeoutException("timed out")));

            // when
            HealthResponse response = healthService.requireHealth();
            HealthResponse cached = healthService.requireHealth();

            // then
            assertEquals(new HealthResponse(false, 200, 503), response);
            assertEquals(response, cached);
            backendServer.verify();
            then(codeforcesService).should().requireCodeforcesHealth();
        }

        @Test
        @DisplayName("갱신 중인 다른 요청을 기다리지 않고 이전 결과를 반환한다")
        void returnsPreviousResultWhileAnotherRequestRefreshes() throws Exception {
            // given
            given(codeforcesService.requireCodeforcesHealth()).willReturn(HttpStatus.OK);
            backendServer.expect(requestTo(BACKEND_HEALTH_URI)).andRespond(withStatus(HttpStatus.OK));
            backendServer.expect(requestTo(BACKEND_HEALTH_URI)).andRespond(withStatus(HttpStatus.OK));
            healthService.requireHealth();
            given(clock.instant()).willReturn(INITIAL_TIME.plusSeconds(61));
            CountDownLatch refreshStarted = new CountDownLatch(1);
            CountDownLatch allowRefresh = new CountDownLatch(1);
            given(codeforcesService.requireCodeforcesHealth()).willAnswer(invocation -> {
                refreshStarted.countDown();
                assertTrue(allowRefresh.await(5, TimeUnit.SECONDS));
                return HttpStatus.SERVICE_UNAVAILABLE;
            });

            // when
            var executor = Executors.newFixedThreadPool(2);
            try {
                var refreshing = executor.submit(healthService::requireHealth);
                assertTrue(refreshStarted.await(2, TimeUnit.SECONDS));
                HealthResponse cached = executor.submit(healthService::requireHealth).get(1, TimeUnit.SECONDS);
                allowRefresh.countDown();
                HealthResponse refreshed = refreshing.get(2, TimeUnit.SECONDS);

                // then
                assertEquals(new HealthResponse(true, 200, 200), cached);
                assertEquals(new HealthResponse(false, 503, 200), refreshed);
                backendServer.verify();
                then(codeforcesService).should(times(2)).requireCodeforcesHealth();
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
            given(codeforcesService.requireCodeforcesHealth()).willAnswer(invocation -> {
                refreshStarted.countDown();
                assertTrue(allowRefresh.await(5, TimeUnit.SECONDS));
                return HttpStatus.OK;
            });
            backendServer.expect(requestTo(BACKEND_HEALTH_URI)).andRespond(withStatus(HttpStatus.OK));

            // when
            var executor = Executors.newFixedThreadPool(2);
            try {
                var refreshing = executor.submit(healthService::requireHealth);
                assertTrue(refreshStarted.await(2, TimeUnit.SECONDS));
                HealthResponse initial = executor.submit(healthService::requireHealth).get(1, TimeUnit.SECONDS);
                allowRefresh.countDown();
                HealthResponse refreshed = refreshing.get(2, TimeUnit.SECONDS);

                // then
                assertEquals(new HealthResponse(false, 503, 503), initial);
                assertEquals(new HealthResponse(true, 200, 200), refreshed);
                backendServer.verify();
                then(codeforcesService).should().requireCodeforcesHealth();
            } finally {
                allowRefresh.countDown();
                executor.shutdownNow();
            }
        }
    }
}
