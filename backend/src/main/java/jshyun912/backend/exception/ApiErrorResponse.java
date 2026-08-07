package jshyun912.backend.exception;

public record ApiErrorResponse(
        String status,
        ErrorMessage message
) {
}
