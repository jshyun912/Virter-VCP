package jshyun912.backend.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.concurrent.locks.ReentrantLock;
import jshyun912.backend.dto.health.HealthResponse;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class HealthService {
    private final RestClient restClient;
    private final Clock clock;
    private final int managementPort;
    private final ReentrantLock refreshLock = new ReentrantLock();

    private volatile LocalDateTime lastChecked = LocalDateTime.MIN;
    private volatile HealthResponse cachedHealth = new HealthResponse(false, 503);

    @Autowired
    public HealthService(RestClient restClient, @Value("${management.server.port:8081}") int managementPort) {
        this(restClient, Clock.systemUTC(), managementPort);
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
        HttpStatusCode backendStatus = requireBackendHealth();
        cachedHealth = HealthResponse.builder()
                .isHealthy(backendStatus.is2xxSuccessful())
                .backendStatus(backendStatus.value())
                .build();
        lastChecked = LocalDateTime.now(clock);
    }

    private HttpStatusCode requireBackendHealth() {
        try {
            return restClient.get()
                    .uri("http://localhost:{port}/actuator/health", managementPort)
                    .exchange((_, response) -> response.getStatusCode());
        } catch (RestClientException exception) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
    }
}
