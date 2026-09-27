package com.repopilot.common.exception;

import java.time.Instant;
import java.util.Map;

public class ErrorResponse {

    private String timestamp;
    private int status;
    private String code;
    private String message;
    private String requestId;
    private Map<String, String> details;

    public ErrorResponse() {
        this.timestamp = Instant.now().toString();
    }

    public ErrorResponse(String timestamp, int status, String code, String message, String requestId, Map<String, String> details) {
        this.timestamp = timestamp != null ? timestamp : Instant.now().toString();
        this.status = status;
        this.code = code;
        this.message = message;
        this.requestId = requestId;
        this.details = details;
    }

    public static ErrorResponseBuilder builder() {
        return new ErrorResponseBuilder();
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public Map<String, String> getDetails() {
        return details;
    }

    public void setDetails(Map<String, String> details) {
        this.details = details;
    }

    public static class ErrorResponseBuilder {
        private String timestamp = Instant.now().toString();
        private int status;
        private String code;
        private String message;
        private String requestId;
        private Map<String, String> details;

        public ErrorResponseBuilder timestamp(String timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public ErrorResponseBuilder status(int status) {
            this.status = status;
            return this;
        }

        public ErrorResponseBuilder code(String code) {
            this.code = code;
            return this;
        }

        public ErrorResponseBuilder message(String message) {
            this.message = message;
            return this;
        }

        public ErrorResponseBuilder requestId(String requestId) {
            this.requestId = requestId;
            return this;
        }

        public ErrorResponseBuilder details(Map<String, String> details) {
            this.details = details;
            return this;
        }

        public ErrorResponse build() {
            return new ErrorResponse(timestamp, status, code, message, requestId, details);
        }
    }
}
