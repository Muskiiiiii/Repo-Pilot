package com.repopilot.testing.daemon.runtime;

public class SandboxDockerConfig {

    public static final String DEFAULT_DOCKER_BINARY = "docker";
    public static final double DEFAULT_CPU_LIMIT_CORES = 2.0;
    public static final int DEFAULT_MEMORY_LIMIT_MB = 2048;
    public static final int DEFAULT_PID_LIMIT = 512;
    public static final int DEFAULT_TIMEOUT_SECONDS = 120;
    public static final int DEFAULT_MAX_OUTPUT_CHARS = 100000;
    public static final String DEFAULT_USER_GID = "65534:65534";
    public static final String NETWORK_NONE = "none";
    public static final String SECCOMP_PROFILE_RESOURCE_PATH = "seccomp/seccomp_unprivileged_strict.json";

    private String dockerBinary = DEFAULT_DOCKER_BINARY;
    private double cpuLimitCores = DEFAULT_CPU_LIMIT_CORES;
    private int memoryLimitMb = DEFAULT_MEMORY_LIMIT_MB;
    private int pidLimit = DEFAULT_PID_LIMIT;
    private int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
    private int maxOutputChars = DEFAULT_MAX_OUTPUT_CHARS;
    private String userGid = DEFAULT_USER_GID;
    private String seccompProfilePath;

    public SandboxDockerConfig() {
    }

    public String getDockerBinary() { return dockerBinary; }
    public void setDockerBinary(String dockerBinary) { this.dockerBinary = dockerBinary; }

    public double getCpuLimitCores() { return cpuLimitCores; }
    public void setCpuLimitCores(double cpuLimitCores) { this.cpuLimitCores = cpuLimitCores; }

    public int getMemoryLimitMb() { return memoryLimitMb; }
    public void setMemoryLimitMb(int memoryLimitMb) { this.memoryLimitMb = memoryLimitMb; }

    public int getPidLimit() { return pidLimit; }
    public void setPidLimit(int pidLimit) { this.pidLimit = pidLimit; }

    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    public int getMaxOutputChars() { return maxOutputChars; }
    public void setMaxOutputChars(int maxOutputChars) { this.maxOutputChars = maxOutputChars; }

    public String getUserGid() { return userGid; }
    public void setUserGid(String userGid) { this.userGid = userGid; }

    public String getSeccompProfilePath() { return seccompProfilePath; }
    public void setSeccompProfilePath(String seccompProfilePath) { this.seccompProfilePath = seccompProfilePath; }
}
