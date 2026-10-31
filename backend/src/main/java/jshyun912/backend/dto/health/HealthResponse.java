package jshyun912.backend.dto.health;

import lombok.Builder;

@Builder
public record HealthResponse(
        boolean isHealthy,
        int cfStatus,
        int backendStatus
) {
}
