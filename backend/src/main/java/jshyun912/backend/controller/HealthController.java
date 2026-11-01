package jshyun912.backend.controller;

import jshyun912.backend.dto.health.HealthResponse;
import jshyun912.backend.service.HealthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class HealthController {
    private final HealthService healthService;

    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        HealthResponse healthResponse = healthService.requireHealth();

        return ResponseEntity
                .status(healthResponse.isHealthy() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(healthResponse);
    }
}
