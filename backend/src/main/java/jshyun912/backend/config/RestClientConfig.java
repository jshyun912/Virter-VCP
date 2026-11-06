package jshyun912.backend.config;

import java.net.http.HttpClient;
import java.time.Duration;
import jshyun912.backend.service.RedisService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {
    @Bean
    public RestClient restClient(
            @Value("${app.codeforces.connect-timeout:5s}") Duration connectTimeout,
            @Value("${app.codeforces.read-timeout:30s}") Duration readTimeout
    ) {
        // 락을 보유한 동안의 Redis 명령과 응답 처리를 위해 20초의 여유를 남긴다.
        Duration maxHttpTimeout = RedisService.LOCK_TTL.minusSeconds(20);
        if (connectTimeout.isZero() || connectTimeout.isNegative()
                || readTimeout.isZero() || readTimeout.isNegative()
                || connectTimeout.plus(readTimeout).compareTo(maxHttpTimeout) > 0) {
            throw new IllegalArgumentException(
                    "Codeforces connect/read 타임아웃은 양수이며 합계가 "
                            + maxHttpTimeout.toSeconds() + "초 이하여야 합니다.");
        }
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(requestFactory).build();
    }
}
