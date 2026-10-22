package jshyun912.backend.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
class RestClientConfigTest {
    @Test
    @DisplayName("Codeforces 요청이 read timeout을 초과하면 예외가 발생한다")
    void readTimeoutStopsASlowCodeforcesRequest() throws IOException, InterruptedException {
        CountDownLatch requestStarted = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                requestStarted.countDown();
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
                    () -> client.get().uri("http://127.0.0.1:" + server.getAddress().getPort())
                            .retrieve()
                            .toBodilessEntity()));
            assertTrue(requestStarted.await(1, TimeUnit.SECONDS));
        } finally {
            server.stop(0);
        }
    }
}
