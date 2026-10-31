package jshyun912.backend.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.concurrent.locks.ReentrantLock;
import jshyun912.backend.dto.health.HealthResponse;
import jshyun912.backend.service.codeforces.CodeforcesService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class HealthService {
    private final RestClient restClient;
    private final CodeforcesService codeforcesService;
    private final Clock clock;
    private final ReentrantLock refreshLock = new ReentrantLock();

    private volatile LocalDateTime lastChecked = LocalDateTime.MIN;
    private volatile HealthResponse cachedHealth = new HealthResponse(false, 503, 503);

    @Autowired
    public HealthService(RestClient restClient, CodeforcesService codeforcesService) {
        this(restClient, codeforcesService, Clock.systemUTC());
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
        return LocalDateTime.now(clock).isAfter(lastChecked.plusMinutes(1));
    }

    private void refreshHealth() {
        HttpStatusCode cfStatus = codeforcesService.requireCodeforcesHealth();
        HttpStatusCode backendStatus = requireBackendHealth();
        cachedHealth = HealthResponse.builder()
                .isHealthy(cfStatus.is2xxSuccessful() && backendStatus.is2xxSuccessful())
                .cfStatus(cfStatus.value())
                .backendStatus(backendStatus.value())
                .build();
        lastChecked = LocalDateTime.now(clock);
    }

    private HttpStatusCode requireBackendHealth() {
        try {
            return restClient.get()
                    .uri("http://localhost:8081/actuator/health")
                    .exchange((_, response) -> response.getStatusCode());
        } catch (RestClientException exception) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
    }
}
