package com.blooddonor.exception;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail notFound(NotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ProblemDetail businessRule(BusinessRuleException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(ForbiddenException.class)
    public ProblemDetail forbidden(ForbiddenException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ProblemDetail invalidCredentials(InvalidCredentialsException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ProblemDetail invalidRefresh(InvalidRefreshTokenException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    @ExceptionHandler(InvalidOtpException.class)
    public ProblemDetail invalidOtp(InvalidOtpException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<ProblemDetail> tooMany(TooManyRequestsException ex) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
        var res = ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS);
        if (ex.retryAfterSeconds() > 0) {
            body.setProperty("retryAfterSeconds", ex.retryAfterSeconds());
            res.header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.retryAfterSeconds()));
        }
        return res.body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail badRequest(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail validation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(e -> errors.putIfAbsent(e.getField(), e.getDefaultMessage()));
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail typeMismatch(MethodArgumentTypeMismatchException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Invalid value '" + ex.getValue() + "' for parameter '" + ex.getName() + "'");
    }
}
