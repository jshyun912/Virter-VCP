package jshyun912.backend.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.concurrent.locks.ReentrantLock;
import jshyun912.backend.dto.health.HealthResponse;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.CompositeHealthDescriptor;
import org.springframework.boot.health.contributor.Status;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class HealthService {
    private final HealthEndpoint healthEndpoint;
    private final Clock clock;
    private final ReentrantLock refreshLock = new ReentrantLock();

    private volatile LocalDateTime lastChecked = LocalDateTime.MIN;
    private volatile HealthResponse cachedHealth = new HealthResponse(false, 503, 503);

    @Autowired
    public HealthService(HealthEndpoint healthEndpoint, @Value("${management.server.port:8081}") int managementPort) {
        this(healthEndpoint, Clock.systemUTC());
        if (managementPort != 8081) {
            throw new IllegalArgumentException(
                    "management.server.port는 8081로 고정되어 있습니다. 설정값: " + managementPort);
        }
    }

    public HealthResponse requireHealth() {
        if (!isCacheExpired() || !refreshLock.tryLock()) {
            return cachedHealth;
        }

        try {
            if (isCacheExpired()) {
                refreshHealth();
            }
            return cachedHealth;
        } finally {
            refreshLock.unlock();
        }
    }

    private boolean isCacheExpired() {
        LocalDateTime checkedAt = lastChecked;
        LocalDateTime now = LocalDateTime.now(clock);
        return now.isBefore(checkedAt) || now.isAfter(checkedAt.plusMinutes(1));
    }

    private void refreshHealth() {
        var health = healthEndpoint.health();
        boolean backendHealthy = Status.UP.equals(health.getStatus());
        if (health instanceof CompositeHealthDescriptor composite) {
            backendHealthy &= composite.getComponents().values().stream()
                    .allMatch(component -> Status.UP.equals(component.getStatus()));
        }
        int backendStatus = backendHealthy ? 200 : 503;
        var redisHealth = health instanceof CompositeHealthDescriptor composite
                ? composite.getComponents().get("redis") : null;
        int redisStatus = redisHealth != null && Status.UP.equals(redisHealth.getStatus()) ? 200 : 503;
        boolean healthy = HttpStatusCode.valueOf(backendStatus).is2xxSuccessful()
                && HttpStatusCode.valueOf(redisStatus).is2xxSuccessful();
        cachedHealth = HealthResponse.builder()
                .isHealthy(healthy)
                .backendStatus(backendStatus)
                .redisStatus(redisStatus)
                .build();
        lastChecked = LocalDateTime.now(clock);
    }
}
