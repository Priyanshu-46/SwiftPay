package com.swiftpay.exception;

public class DuplicatePaymentException extends RuntimeException {
    public DuplicatePaymentException(String idempotencyKey) {
        super("Duplicate payment request for idempotency key: " + idempotencyKey);
    }
}
