package jshyun912.backend.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class GlobalExceptionHandlerTests {

    @Test
    void returnsApiExceptionStatusAndMessage() {
        ApiException exception = new ApiException(
                HttpStatus.BAD_GATEWAY,
                ErrorMessage.CODEFORCES_API_FAILED,
                "Codeforces 제출 목록을 불러오지 못했습니다."
        );

        ResponseEntity<ApiErrorResponse> response = new GlobalExceptionHandler().handleApiException(exception);

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertEquals("FAILED", response.getBody().status());
        assertEquals(ErrorMessage.CODEFORCES_API_FAILED, response.getBody().message());
        assertEquals("Codeforces 제출 목록을 불러오지 못했습니다.", exception.getDetail());
    }
}
