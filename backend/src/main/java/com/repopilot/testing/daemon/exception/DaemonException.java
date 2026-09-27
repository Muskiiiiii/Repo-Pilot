package com.repopilot.testing.daemon.exception;

import com.repopilot.testing.daemon.model.DaemonStatus;

public class DaemonException extends RuntimeException {

    private final DaemonStatus status;
    private final String errorCode;

    public DaemonException(DaemonStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public DaemonStatus getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
