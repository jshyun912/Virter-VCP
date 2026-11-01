package jshyun912.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import jshyun912.backend.dto.health.HealthResponse;
import jshyun912.backend.service.codeforces.CodeforcesService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@ExtendWith(MockitoExtension.class)
@DisplayName("HealthService 설정")
class HealthServiceConfigurationTest {
    @Mock
    private CodeforcesService codeforcesService;

    @Test
    @DisplayName("관리 포트 설정을 재정의하면 해당 포트로 백엔드 상태를 조회한다")
    void usesConfiguredManagementPort() {
        // given
        given(codeforcesService.requireCodeforcesHealth()).willReturn(HttpStatus.OK);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer backendServer = MockRestServiceServer.bindTo(builder).build();
        backendServer.expect(requestTo("http://localhost:9091/actuator/health"))
                .andRespond(withStatus(HttpStatus.OK));

        try (var context = new AnnotationConfigApplicationContext()) {
            TestPropertyValues.of("management.server.port=9091").applyTo(context);
            context.registerBean(RestClient.class, builder::build);
            context.registerBean(CodeforcesService.class, () -> codeforcesService);
            context.register(HealthService.class);
            context.refresh();

            // when
            HealthResponse response = context.getBean(HealthService.class).requireHealth();

            // then
            assertEquals(new HealthResponse(true, 200, 200), response);
            backendServer.verify();
        }
    }
}
