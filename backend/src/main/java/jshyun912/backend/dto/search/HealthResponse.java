package jshyun912.backend.dto.search;

import lombok.Builder;

@Builder 
public record HealthResponse(
    boolean isHealthy,
    int cf_status,
    int backend_status
) {
}
