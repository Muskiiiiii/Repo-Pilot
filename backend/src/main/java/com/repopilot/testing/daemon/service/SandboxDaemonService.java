package com.repopilot.testing.daemon.service;

import com.repopilot.testing.daemon.model.DaemonExecutionRequest;
import com.repopilot.testing.daemon.model.DaemonExecutionResponse;

public interface SandboxDaemonService {

    DaemonExecutionResponse processRequest(DaemonExecutionRequest request);
}
