package jshyun912.backend.controller;

import org.springframework.web.bind.annotation.RestController;

import jshyun912.backend.dto.search.HealthResponse;
import jshyun912.backend.service.codeforces.CodeforcesService;
import lombok.AllArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;

@RestController 
@AllArgsConstructor 
public class HealthController {
    private final CodeforcesService codeforcesService;
    @GetMapping("/health")
    public ResponseEntity<HealthResponse> getMethodName() {
        int cf_status = codeforcesService.requireCodeforcesHealth().value();
        Boolean isHealthy = cf_status < 300;

        HealthResponse healthResponse = HealthResponse.builder()
                .isHealthy(isHealthy)
                .cf_status(cf_status)
                .backend_status(200)
                .build();

        return ResponseEntity
        .status(healthResponse.isHealthy() ? 200 : 503)
        .body(healthResponse);
    }
}
