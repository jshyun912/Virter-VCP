package jshyun912.backend.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@Tag("integration")
class RestClientConfigTest {
    @Test
    @DisplayName("API 주소를 설정하지 않으면 기존 Codeforces 주소를 사용한다")
    void defaultsToPublicCodeforcesApi() {
        // Given
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance());
            context.register(RestClientConfig.class);
            context.refresh();
            RestClient.Builder builder = context.getBean(RestClient.class).mutate();
            var server = MockRestServiceServer.bindTo(builder).build();
            server.expect(requestTo("https://codeforces.com/api/user.status?handle=tourist"))
                    .andRespond(withSuccess());

            // When
            builder.build().get().uri("/user.status?handle={handle}", "tourist").retrieve().toBodilessEntity();

            // Then
            server.verify();
        }
    }

    @Test
    @DisplayName("Codeforces 요청이 read timeout을 초과하면 예외가 발생한다")
    void readTimeoutStopsASlowCodeforcesRequest() throws IOException {
        // Given
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ready", exchange -> {
            try {
                exchange.sendResponseHeaders(200, -1);
            } finally {
                exchange.close();
            }
        });
        server.createContext("/slow", exchange -> {
            try {
                requestStarted.countDown();
                releaseResponse.await(10, TimeUnit.SECONDS);
                exchange.sendResponseHeaders(200, -1);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        try {
            var client = new RestClientConfig().restClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    Duration.ofSeconds(5), Duration.ofSeconds(1));
            // 첫 HTTP 요청의 초기화 비용을 지연 응답 검증과 분리한다.
            client.get().uri("/ready").retrieve().toBodilessEntity();

            // When
            var exception = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertThrows(
                    ResourceAccessException.class,
                    () -> client.get().uri("/slow")
                            .retrieve()
                            .toBodilessEntity()));

            // Then
            assertEquals(0, requestStarted.getCount(), "서버가 요청을 받은 뒤 응답 대기 중에 만료되어야 한다");
            assertInstanceOf(HttpTimeoutException.class, exception.getCause());
        } finally {
            releaseResponse.countDown();
            server.stop(0);
        }
    }
}
