package jshyun912.backend.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @Override
    protected ResponseEntity<Object> createResponseEntity(
            Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ErrorMessage message = statusCode.is4xxClientError()
                ? ErrorMessage.HTTP_REQUEST_FAILED
                : ErrorMessage.INTERNAL_SERVER_ERROR;
        return new ResponseEntity<>(new ApiErrorResponse("FAILED", message), headers, statusCode);
    }

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
