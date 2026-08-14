package jshyun912.backend.config;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {
    public RestClient restClient() {
        return restClient(Duration.ofSeconds(5), Duration.ofSeconds(30));
    }

    @Bean
    public RestClient restClient(
            @Value("${app.codeforces.connect-timeout:5s}") Duration connectTimeout,
            @Value("${app.codeforces.read-timeout:30s}") Duration readTimeout
    ) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(requestFactory).build();
    }
}
