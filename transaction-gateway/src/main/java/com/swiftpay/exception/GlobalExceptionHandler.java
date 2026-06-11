package com.swiftpay.exception;

import com.swiftpay.dto.PaymentDtos;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.time.Instant;
import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<PaymentDtos.ErrorResponse> handleInsufficientFunds(
            InsufficientFundsException ex, WebRequest request) {
        log.warn("Insufficient funds: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(buildError("INSUFFICIENT_FUNDS", ex.getMessage(), request));
    }

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<PaymentDtos.ErrorResponse> handleAccountNotFound(
            AccountNotFoundException ex, WebRequest request) {
        log.warn("Account not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(buildError("ACCOUNT_NOT_FOUND", ex.getMessage(), request));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<PaymentDtos.ErrorResponse> handleValidation(
            MethodArgumentNotValidException ex, WebRequest request) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(buildError("VALIDATION_ERROR", msg, request));
    }

    @ExceptionHandler(DuplicatePaymentException.class)
    public ResponseEntity<PaymentDtos.ErrorResponse> handleDuplicate(
            DuplicatePaymentException ex, WebRequest request) {
        log.info("Duplicate payment request, returning cached result: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.OK)
                .body(buildError("DUPLICATE_REQUEST", ex.getMessage(), request));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<PaymentDtos.ErrorResponse> handleGeneric(
            Exception ex, WebRequest request) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(buildError("INTERNAL_ERROR", "An unexpected error occurred", request));
    }

    private PaymentDtos.ErrorResponse buildError(String code, String message, WebRequest request) {
        return PaymentDtos.ErrorResponse.builder()
                .code(code)
                .message(message)
                .requestId(request.getHeader("X-Request-ID"))
                .timestamp(Instant.now())
                .build();
    }
}
