package com.changeguard.connector.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = GitHubConnectionController.class)
public class GitHubConnectionErrors {
    private static final Logger log = LoggerFactory.getLogger(GitHubConnectionErrors.class);

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ProblemDetail unavailable(RuntimeException exception) {
        log.warn("GitHub connection persistence unavailable ({})", exception.getClass().getSimpleName());
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "GitHub connection persistence is unavailable");
    }
}
