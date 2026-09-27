package com.repopilot.testing.dto;

import com.repopilot.testing.entity.FailureType;
import com.repopilot.testing.entity.TerminationReason;

public class DockerExecutionResult {

    private final Integer exitCode;
    private final String stdout;
    private final String stderr;
    private final Long durationMs;
    private final String containerId;
    private final FailureType failureType;
    private final boolean stdoutTruncated;
    private final boolean stderrTruncated;
    private final String commandExecuted;
    private final TerminationReason terminationReason;
    private final long stdoutBytesObserved;
    private final long stderrBytesObserved;

    public DockerExecutionResult(Integer exitCode, String stdout, String stderr, Long durationMs,
                                 String containerId, FailureType failureType,
                                 boolean stdoutTruncated, boolean stderrTruncated, String commandExecuted) {
        this(exitCode, stdout, stderr, durationMs, containerId, failureType, stdoutTruncated, stderrTruncated, commandExecuted,
                exitCode != null && exitCode == 0 ? TerminationReason.NORMAL_EXIT : (failureType == FailureType.TIMEOUT ? TerminationReason.TIMEOUT : TerminationReason.NORMAL_EXIT),
                stdout != null ? stdout.getBytes().length : 0L,
                stderr != null ? stderr.getBytes().length : 0L);
    }

    public DockerExecutionResult(Integer exitCode, String stdout, String stderr, Long durationMs,
                                 String containerId, FailureType failureType,
                                 boolean stdoutTruncated, boolean stderrTruncated, String commandExecuted,
                                 TerminationReason terminationReason, long stdoutBytesObserved, long stderrBytesObserved) {
        this.exitCode = exitCode;
        this.stdout = stdout;
        this.stderr = stderr;
        this.durationMs = durationMs;
        this.containerId = containerId;
        this.failureType = failureType;
        this.stdoutTruncated = stdoutTruncated;
        this.stderrTruncated = stderrTruncated;
        this.commandExecuted = commandExecuted;
        this.terminationReason = terminationReason != null ? terminationReason : TerminationReason.NORMAL_EXIT;
        this.stdoutBytesObserved = stdoutBytesObserved;
        this.stderrBytesObserved = stderrBytesObserved;
    }

    public Integer getExitCode() {
        return exitCode;
    }

    public String getStdout() {
        return stdout;
    }

    public String getStderr() {
        return stderr;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public String getContainerId() {
        return containerId;
    }

    public FailureType getFailureType() {
        return failureType;
    }

    public boolean isStdoutTruncated() {
        return stdoutTruncated;
    }

    public boolean isStderrTruncated() {
        return stderrTruncated;
    }

    public String getCommandExecuted() {
        return commandExecuted;
    }

    public TerminationReason getTerminationReason() {
        return terminationReason;
    }

    public long getStdoutBytesObserved() {
        return stdoutBytesObserved;
    }

    public long getStderrBytesObserved() {
        return stderrBytesObserved;
    }
}
