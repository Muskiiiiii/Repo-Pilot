package com.repopilot.testing.exception;

public class StaleWorkerFencingException extends RuntimeException {
    public StaleWorkerFencingException(String message) {
        super(message);
    }
}
