package jshyun912.backend.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleException(Exception exception) {
        log.error("예상하지 못한 서버 오류가 발생했습니다.", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiErrorResponse("FAILED", ErrorMessage.INTERNAL_SERVER_ERROR));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> handleApiException(ApiException exception) {
        log.error("API 오류: {} - {}", exception.getErrorMessage(), exception.getDetail(), exception);
        return ResponseEntity.status(exception.getHttpStatus())
                .body(new ApiErrorResponse("FAILED", exception.getErrorMessage()));
    }
}
