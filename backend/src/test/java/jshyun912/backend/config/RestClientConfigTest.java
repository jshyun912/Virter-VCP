package jshyun912.backend.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class RestClientConfigTest {
    @Test
    void readTimeoutStopsASlowCodeforcesRequest() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            try {
                Thread.sleep(1000);
                exchange.sendResponseHeaders(200, 0);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        try {
            var client = new RestClientConfig().restClient(Duration.ofSeconds(1), Duration.ofMillis(50));
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertThrows(
                    RestClientException.class,
                    () -> client.get().uri("http://localhost:" + server.getAddress().getPort())
                            .retrieve()
                            .toBodilessEntity()));
        } finally {
            server.stop(0);
        }
    }
}
