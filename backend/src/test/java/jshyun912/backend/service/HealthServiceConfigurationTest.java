package jshyun912.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jshyun912.backend.dto.health.HealthResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.health.autoconfigure.actuate.endpoint.HealthEndpointAutoConfiguration;
import org.springframework.boot.health.autoconfigure.registry.HealthContributorRegistryAutoConfiguration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

@DisplayName("HealthService 설정")
class HealthServiceConfigurationTest {
    @ParameterizedTest
    @CsvSource({"UP, true, 200", "DOWN, false, 503", "OUT_OF_SERVICE, false, 503", "UNKNOWN, false, 503"})
    @DisplayName("관리 포트가 8081이면 HTTP 클라이언트 없이 Actuator 상태를 반영한다")
    void readsActuatorHealthWithoutHttpClient(String status, boolean healthy, int backendStatus) {
        // given
        try (var context = new AnnotationConfigApplicationContext()) {
            TestPropertyValues.of("management.server.port=8081").applyTo(context);
            context.registerBean("backendHealthIndicator", HealthIndicator.class,
                    () -> () -> Health.status(status).build());
            context.registerBean("redisHealthIndicator", HealthIndicator.class,
                    () -> () -> Health.up().build());
            context.register(HealthContributorRegistryAutoConfiguration.class,
                    HealthEndpointAutoConfiguration.class, HealthService.class);
            context.refresh();

            // when
            HealthResponse response = context.getBean(HealthService.class).requireHealth();

            // then
            assertEquals(new HealthResponse(healthy, backendStatus, 200), response);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"DOWN", "OUT_OF_SERVICE", "UNKNOWN"})
    @DisplayName("Redis가 UP 이외의 상태이면 전체 상태와 redisStatus를 실패로 처리한다")
    void rejectsUnhealthyRedis(String redisStatus) {
        // given
        try (var context = new AnnotationConfigApplicationContext()) {
            TestPropertyValues.of("management.server.port=8081").applyTo(context);
            context.registerBean("backendHealthIndicator", HealthIndicator.class,
                    () -> () -> Health.up().build());
            context.registerBean("redisHealthIndicator", HealthIndicator.class,
                    () -> () -> Health.status(redisStatus).build());
            context.register(HealthContributorRegistryAutoConfiguration.class,
                    HealthEndpointAutoConfiguration.class, HealthService.class);
            context.refresh();

            // when
            HealthResponse response = context.getBean(HealthService.class).requireHealth();

            // then
            assertEquals(new HealthResponse(false, 503, 503), response);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 9091})
    @DisplayName("관리 포트가 8081이 아니면 애플리케이션 시작을 거부한다")
    void rejectsOtherManagementPorts(int managementPort) {
        // given
        try (var context = new AnnotationConfigApplicationContext()) {
            TestPropertyValues.of("management.server.port=" + managementPort).applyTo(context);
            context.register(HealthContributorRegistryAutoConfiguration.class,
                    HealthEndpointAutoConfiguration.class, HealthService.class);

            // when
            BeanCreationException exception = assertThrows(BeanCreationException.class, context::refresh);

            // then
            IllegalArgumentException cause = assertInstanceOf(
                    IllegalArgumentException.class, exception.getMostSpecificCause());
            assertTrue(cause.getMessage().contains("management.server.port"));
            assertTrue(cause.getMessage().contains("8081"));
        }
    }
}
