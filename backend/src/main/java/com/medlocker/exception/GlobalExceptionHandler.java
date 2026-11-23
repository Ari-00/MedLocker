package com.medlocker.exception;

import com.medlocker.dto.Api;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public static Api.Error payload(HttpStatusCode status, String message) {
        HttpStatus known = HttpStatus.resolve(status.value());
        return new Api.Error(Instant.now(), status.value(), known == null ? "Request failed" : known.getReasonPhrase(), message);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception failure, Object body, HttpHeaders headers,
                                                              HttpStatusCode status, WebRequest request) {
        String message = switch (failure) {
            case ResponseStatusException known when known.getReason() != null -> known.getReason();
            case MaxUploadSizeExceededException ignored -> "Choose a file up to 10 MB.";
            case MethodArgumentNotValidException invalid -> validationMessage(invalid);
            default -> status.is5xxServerError() ? "The service could not complete your request." : "Check your request and try again.";
        };
        return super.handleExceptionInternal(failure, payload(status, message), headers, status, request);
    }

    private static String validationMessage(MethodArgumentNotValidException failure) {
        var field = failure.getBindingResult().getFieldError();
        if (field == null) return "Check your request and try again.";
        return switch (field.getField()) {
            case "pin" -> "Enter a six-digit PIN.";
            case "email" -> "Enter a valid email address.";
            case "fullName" -> "Enter a full name up to 120 characters.";
            case "password" -> "Check the password length.";
            default -> "Check your request and try again.";
        };
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Api.Error> unexpected(Exception failure) {
        log.atError().addKeyValue("exception_type", failure.getClass().getName())
                .log("MedLocker request failed");
        return ResponseEntity.internalServerError().body(payload(HttpStatus.INTERNAL_SERVER_ERROR,
                "The service could not complete your request."));
    }
}
