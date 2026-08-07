package jshyun912.backend.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class ApiException extends RuntimeException {
    private final HttpStatus httpStatus;
    private final ErrorMessage errorMessage;
    private final String detail;

    public ApiException(HttpStatus httpStatus, ErrorMessage errorMessage, String detail) {
        this.httpStatus = httpStatus;
        this.errorMessage = errorMessage;
        this.detail = detail;
    }

    public ApiException(HttpStatus httpStatus, ErrorMessage errorMessage, String detail, Throwable cause) {
        super(cause);
        this.httpStatus = httpStatus;
        this.errorMessage = errorMessage;
        this.detail = detail;
    }

}
