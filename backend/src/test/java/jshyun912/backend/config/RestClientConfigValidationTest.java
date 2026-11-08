package jshyun912.backend.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("Codeforces HTTP 타임아웃 설정 검증")
class RestClientConfigValidationTest {
    @ParameterizedTest
    @CsvSource({"0,30", "-1,30", "5,0", "5,-1", "5,36", "30,30", "5,60"})
    @DisplayName("무한·음수 또는 연결과 읽기 합계가 40초를 초과하는 타임아웃을 거부한다")
    void rejectsTimeoutsWithoutEnoughLockMargin(long connectSeconds, long readSeconds) {
        // given
        var config = new RestClientConfig();

        // when / then
        assertThrows(IllegalArgumentException.class, () -> config.restClient(
                Duration.ofSeconds(connectSeconds), Duration.ofSeconds(readSeconds)));
    }

    @ParameterizedTest
    @CsvSource({"5,30", "5,35", "10,30", "1,1"})
    @DisplayName("양수이며 연결과 읽기 합계가 40초 이하인 타임아웃을 허용한다")
    void acceptsTimeoutsWithEnoughLockMargin(long connectSeconds, long readSeconds) {
        // given
        var config = new RestClientConfig();

        // when / then
        assertDoesNotThrow(() -> config.restClient(
                Duration.ofSeconds(connectSeconds), Duration.ofSeconds(readSeconds)));
    }
}
