package jshyun912.backend.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jshyun912.backend.dto.health.HealthResponse;
import jshyun912.backend.service.HealthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(HealthController.class)
@DisplayName("HealthController")
class HealthControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HealthService healthService;

    @Nested
    @DisplayName("GET /health를 요청하면")
    class GetHealth {
        @Test
        @DisplayName("정상 상태에는 HTTP 200과 서비스의 점검 결과를 반환한다")
        void returnsOkWhenHealthy() throws Exception {
            // given
            given(healthService.requireHealth()).willReturn(new HealthResponse(true, 200, 200));

            // when / then
            mockMvc.perform(get("/health"))
                    .andExpect(status().isOk())
                    .andExpect(content().json("""
                            {"isHealthy":true,"backendStatus":200,"redisStatus":200}
                            """))
                    .andExpect(jsonPath("$.cfStatus").doesNotHaveJsonPath());
            then(healthService).should().requireHealth();
        }

        @Test
        @DisplayName("Redis 상태가 비정상이면 HTTP 503과 Redis 점검 결과를 반환한다")
        void returnsUnavailableWhenRedisIsUnhealthy() throws Exception {
            // given
            given(healthService.requireHealth()).willReturn(new HealthResponse(false, 200, 503));

            // when / then
            mockMvc.perform(get("/health"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.isHealthy").value(false))
                    .andExpect(jsonPath("$.backendStatus").value(200))
                    .andExpect(jsonPath("$.redisStatus").value(503));
            then(healthService).should().requireHealth();
        }

        @Test
        @DisplayName("비정상 상태에는 HTTP 503과 서비스의 점검 결과를 반환한다")
        void returnsServiceUnavailableWhenUnhealthy() throws Exception {
            // given
            given(healthService.requireHealth()).willReturn(new HealthResponse(false, 503, 200));

            // when / then
            mockMvc.perform(get("/health"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(content().json("""
                            {"isHealthy":false,"backendStatus":503,"redisStatus":200}
                            """))
                    .andExpect(jsonPath("$.cfStatus").doesNotHaveJsonPath());
            then(healthService).should().requireHealth();
        }
    }
}
