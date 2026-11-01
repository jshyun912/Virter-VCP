package jshyun912.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import jshyun912.backend.dto.health.HealthResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@DisplayName("HealthService 설정")
class HealthServiceConfigurationTest {
    @Test
    @DisplayName("관리 포트가 8081이면 백엔드 상태를 조회한다")
    void usesFixedManagementPort() {
        // given
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer backendServer = MockRestServiceServer.bindTo(builder).build();
        backendServer.expect(requestTo("http://localhost:8081/actuator/health"))
                .andRespond(withStatus(HttpStatus.OK));

        try (var context = new AnnotationConfigApplicationContext()) {
            TestPropertyValues.of("management.server.port=8081").applyTo(context);
            context.registerBean(RestClient.class, builder::build);
            context.register(HealthService.class);
            context.refresh();

            // when
            HealthResponse response = context.getBean(HealthService.class).requireHealth();

            // then
            assertTrue(response.isHealthy());
            assertEquals(200, response.backendStatus());
            backendServer.verify();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 9091})
    @DisplayName("관리 포트가 8081이 아니면 애플리케이션 시작을 거부한다")
    void rejectsOtherManagementPorts(int managementPort) {
        // given
        try (var context = new AnnotationConfigApplicationContext()) {
            TestPropertyValues.of("management.server.port=" + managementPort).applyTo(context);
            context.registerBean(RestClient.class, () -> RestClient.create());
            context.register(HealthService.class);

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
