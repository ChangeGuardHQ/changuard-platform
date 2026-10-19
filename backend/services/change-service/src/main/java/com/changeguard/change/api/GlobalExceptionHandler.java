package com.changeguard.change.api;

import com.changeguard.change.exception.ChangeNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Return stable problem details without echoing query values, persistence errors, or tenant data. */
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(ChangeNotFoundException.class)
    ProblemDetail notFound() { return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Change not found"); }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail forbidden() { return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Organization access denied"); }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentTypeMismatchException.class})
    ProblemDetail badQuery() { return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid change query"); }
}
