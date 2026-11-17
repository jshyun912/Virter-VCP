package jshyun912.backend.service.codeforces;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import jshyun912.backend.config.RestClientConfig;
import jshyun912.backend.dto.codeforces.Contest;
import jshyun912.backend.service.RedisService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

@Tag("integration")
@ExtendWith(MockitoExtension.class)
@DisplayName("Codeforces API 기본 주소 설정")
class CodeforcesBaseUrlIntegrationTest {
    @TempDir
    private Path divisionsDirectory;

    @Mock
    private RedisService redisService;

    @Test
    @DisplayName("환경 변수의 API 주소로 제출 기록과 대회 목록을 조회한다")
    void usesConfiguredBaseUrlForBothCodeforcesEndpoints() throws Exception {
        // Given
        for (String fileName : List.of("Div1.json", "Div1Plus2.json", "Div2.json", "Div2Educational.json",
                "Div3.json", "Div4.json", "others.json")) {
            Files.writeString(divisionsDirectory.resolve(fileName), "{\"status\":\"OK\",\"result\":[]}");
        }
        List<String> requests = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/", exchange -> {
            requests.add(exchange.getRequestURI().toString());
            String body = exchange.getRequestURI().getPath().endsWith("user.status")
                    ? "{\"status\":\"OK\",\"result\":[{\"contestId\":123}]}"
                    : """
                    {"status":"OK","result":[
                      {"id":456,"name":"Codeforces Round 456 (Div. 2)","phase":"FINISHED"}
                    ]}
                    """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();

        try (var context = new AnnotationConfigApplicationContext()) {
            context.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance());
            String authority = "127.0.0.1:" + server.getAddress().getPort();
            context.getEnvironment().getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                    "codeforces-test", Map.of("APP_CODEFORCES_BASE_URL", "http://" + authority + "/api")));
            context.register(RestClientConfig.class);
            context.refresh();
            RestClient client = context.getBean(RestClient.class).mutate()
                    .requestInterceptor((request, body, execution) -> {
                        assertEquals(authority, request.getURI().getAuthority());
                        return execution.execute(request, body);
                    }).build();
            var service = new CodeforcesService(client, JsonMapper.builder().build(), redisService);
            service.setDivisionsDirectory(divisionsDirectory);

            // When
            var submissions = service.getSubmissions("tourist");
            service.initContestList();

            // Then
            assertEquals(123, submissions.result().getFirst().contestId());
            assertEquals(List.of(456), service.getContestCandidates(4).stream().map(Contest::id).toList());
            assertEquals(List.of("/api/user.status?handle=tourist", "/api/contest.list?gym=false"), requests);
        } finally {
            server.stop(0);
        }
    }
}
