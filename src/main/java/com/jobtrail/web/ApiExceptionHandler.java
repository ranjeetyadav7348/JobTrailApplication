package com.jobtrail.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.List;

/** Turns failures into a small, predictable JSON shape the UI can display. */
@RestControllerAdvice
@Slf4j
public class ApiExceptionHandler {

    public record ErrorBody(String error, int status, Instant timestamp, List<String> details) {
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorBody> handleApi(ApiException e) {
        return ResponseEntity.status(e.getStatus())
                .body(new ErrorBody(e.getMessage(), e.getStatus().value(), Instant.now(), List.of()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorBody> handleValidation(MethodArgumentNotValidException e) {
        List<String> details = e.getBindingResult().getFieldErrors().stream()
                .map(this::describe)
                .toList();
        String message = details.isEmpty() ? "That request was not valid." : details.get(0);
        return ResponseEntity.badRequest()
                .body(new ErrorBody(message, 400, Instant.now(), details));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorBody> handleMissingResource(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorBody("Not found", 404, Instant.now(), List.of()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> handleUnexpected(Exception e) {
        log.error("Unhandled failure", e);
        String message = e.getMessage() == null || e.getMessage().isBlank()
                ? e.getClass().getSimpleName()
                : e.getMessage();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorBody(message, 500, Instant.now(), List.of()));
    }

    private String describe(FieldError fe) {
        return fe.getDefaultMessage() == null
                ? fe.getField() + " is invalid"
                : fe.getDefaultMessage();
    }
}
