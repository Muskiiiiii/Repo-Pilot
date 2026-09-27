package com.repopilot.github.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

@Component
public class GitAskpassManager {

    private static final Logger logger = LoggerFactory.getLogger(GitAskpassManager.class);
    private static final String ASKPASS_SCRIPT_NAME = "repopilot-git-askpass.sh";

    private final Path askpassExecutablePath;

    public GitAskpassManager() {
        this.askpassExecutablePath = initializeAskpassScript();
    }

    public Path getAskpassExecutablePath() {
        return askpassExecutablePath;
    }

    public void configureProcessBuilder(ProcessBuilder pb, GithubCredentialContext credential) {
        if (pb == null) {
            throw new IllegalArgumentException("ProcessBuilder cannot be null");
        }
        if (credential == null || credential.isWiped()) {
            throw new IllegalStateException("GithubCredentialContext is null or wiped");
        }

        Map<String, String> env = pb.environment();
        env.put("GIT_ASKPASS", askpassExecutablePath.toAbsolutePath().toString());
        env.put("REPOPILOT_ASKPASS_TOKEN", credential.getToken());
        env.put("REPOPILOT_ASKPASS_USER", credential.getUsername());
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("GIT_CONFIG_GLOBAL", "/dev/null");
        env.put("GIT_CONFIG_SYSTEM", "/dev/null");
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_SSH_COMMAND", "/bin/false");
    }

    public static String resolvePrompt(String prompt, GithubCredentialContext credential) {
        return resolvePrompt(prompt, credential, "github.com");
    }

    public static String resolvePrompt(String prompt, GithubCredentialContext credential, String expectedHost) {
        if (prompt == null || credential == null || credential.isWiped()) {
            return null;
        }

        String lowerPrompt = prompt.toLowerCase(Locale.ROOT);

        // Fail-closed security check against shell metacharacters or unexpected prompts
        if (lowerPrompt.contains("\n") || lowerPrompt.contains("\r") || lowerPrompt.contains(";") ||
            lowerPrompt.contains("&") || lowerPrompt.contains("|") || lowerPrompt.contains("`") ||
            lowerPrompt.contains("$(") || lowerPrompt.contains("<") || lowerPrompt.contains(">")) {
            logger.warn("Askpass prompt contains suspicious shell metacharacters or newlines; failing closed.");
            return null;
        }

        // Host binding verification
        if (expectedHost != null && !expectedHost.isBlank()) {
            String lowerHost = expectedHost.toLowerCase(Locale.ROOT);
            if (!lowerPrompt.contains(lowerHost)) {
                logger.warn("Askpass prompt does not match expected host '{}'; failing closed.", expectedHost);
                return null;
            }
        }

        // Must be a Git HTTP/HTTPS credential prompt
        boolean isGitHttpPrompt = lowerPrompt.contains("http://") || lowerPrompt.contains("https://") || lowerPrompt.contains("github.com");

        if (lowerPrompt.contains("username") && (isGitHttpPrompt || lowerPrompt.startsWith("username for"))) {
            return credential.getUsername();
        } else if (lowerPrompt.contains("password") && isGitHttpPrompt) {
            return credential.getToken();
        }

        logger.warn("Askpass received unexpected prompt string; failing closed: {}", SecretRedactor.sanitizeLogs(prompt));
        return null;
    }

    private Path initializeAskpassScript() {
        try {
            Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"), "repopilot-security");
            if (!Files.exists(tempDir)) {
                try {
                    Set<PosixFilePermission> dirPerms = PosixFilePermissions.fromString("rwx------");
                    Files.createDirectories(tempDir, PosixFilePermissions.asFileAttribute(dirPerms));
                } catch (UnsupportedOperationException e) {
                    Files.createDirectories(tempDir);
                }
            }

            Path scriptPath = tempDir.resolve(ASKPASS_SCRIPT_NAME);

            // Symlink defense: remove any existing symlink or file
            if (Files.isSymbolicLink(scriptPath)) {
                Files.delete(scriptPath);
            }

            String scriptContent = "#!/bin/sh\n" +
                    "PROMPT=\"$1\"\n" +
                    "case \"$PROMPT\" in\n" +
                    "    *Username*|*username*)\n" +
                    "        echo \"${REPOPILOT_ASKPASS_USER:-x-access-token}\"\n" +
                    "        ;;\n" +
                    "    *Password*|*password*)\n" +
                    "        if [ -n \"$REPOPILOT_ASKPASS_TOKEN\" ]; then\n" +
                    "            echo \"$REPOPILOT_ASKPASS_TOKEN\"\n" +
                    "        else\n" +
                    "            exit 1\n" +
                    "        fi\n" +
                    "        ;;\n" +
                    "    *)\n" +
                    "        exit 1\n" +
                    "        ;;\n" +
                    "esac\n";

            Files.writeString(scriptPath, scriptContent, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            try {
                Set<PosixFilePermission> filePerms = PosixFilePermissions.fromString("rwx------");
                Files.setPosixFilePermissions(scriptPath, filePerms);
            } catch (UnsupportedOperationException e) {
                File f = scriptPath.toFile();
                f.setReadable(true, true);
                f.setWritable(true, true);
                f.setExecutable(true, true);
            }

            scriptPath.toFile().deleteOnExit();
            return scriptPath.toAbsolutePath();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize GIT_ASKPASS helper script", e);
        }
    }
}
