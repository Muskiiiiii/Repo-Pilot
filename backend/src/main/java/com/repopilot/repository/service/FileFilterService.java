package com.repopilot.repository.service;

import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

@Service
public class FileFilterService {

    private final SecretDetectionService secretDetectionService;

    private static final Set<String> EXCLUDED_DIRECTORIES = new HashSet<>(Arrays.asList(
            ".git", ".svn", ".hg", "node_modules", "target", "build", "dist", ".next",
            "vendor", "venv", ".mvn", ".gradle", ".idea", ".vscode", "out", "bin", "obj", "__pycache__"
    ));

    private static final Set<String> EXCLUDED_EXTENSIONS = new HashSet<>(Arrays.asList(
            "jar", "war", "ear", "class", "exe", "dll", "so", "dylib",
            "png", "jpg", "jpeg", "gif", "bmp", "ico", "svg", "webp",
            "pdf", "zip", "tar", "gz", "7z", "rar", "mp3", "mp4", "wav", "avi",
            "woff", "woff2", "ttf", "eot", "map", "pyc", "pyo"
    ));

    private static final Set<String> EXCLUDED_FILENAMES = new HashSet<>(Arrays.asList(
            "package-lock.json", "yarn.lock", "pnpm-lock.yaml", "cargo.lock", "composer.lock"
    ));

    public FileFilterService(SecretDetectionService secretDetectionService) {
        this.secretDetectionService = secretDetectionService;
    }

    public boolean shouldIndexFile(String path, long sizeBytes, long maxSizeBytes) {
        if (path == null || path.isBlank()) {
            return false;
        }

        if (maxSizeBytes > 0 && sizeBytes > maxSizeBytes) {
            return false;
        }

        if (secretDetectionService.isSecretFilename(path)) {
            return false;
        }

        String normalizedPath = path.replace("\\", "/");

        // Check directory exclusions
        String[] parts = normalizedPath.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if (EXCLUDED_DIRECTORIES.contains(parts[i])) {
                return false;
            }
        }

        String fileName = parts[parts.length - 1];
        if (EXCLUDED_FILENAMES.contains(fileName.toLowerCase())) {
            return false;
        }

        if (fileName.endsWith(".min.js") || fileName.endsWith(".min.css")) {
            return false;
        }

        String ext = getFileExtension(fileName).toLowerCase();
        if (EXCLUDED_EXTENSIONS.contains(ext)) {
            return false;
        }

        return true;
    }

    public String detectLanguage(String path) {
        if (path == null) {
            return "unknown";
        }
        String fileName = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        String ext = getFileExtension(fileName).toLowerCase();

        switch (ext) {
            case "java": return "java";
            case "py": return "python";
            case "js": case "jsx": case "mjs": case "cjs": return "javascript";
            case "ts": case "tsx": return "typescript";
            case "go": return "go";
            case "cpp": case "cc": case "cxx": case "h": case "hpp": return "cpp";
            case "c": return "c";
            case "cs": return "csharp";
            case "rs": return "rust";
            case "rb": return "ruby";
            case "php": return "php";
            case "kt": case "kts": return "kotlin";
            case "scala": return "scala";
            case "sql": return "sql";
            case "md": case "markdown": return "markdown";
            case "json": return "json";
            case "yml": case "yaml": return "yaml";
            case "xml": return "xml";
            case "sh": case "bash": return "bash";
            default:
                if ("dockerfile".equalsIgnoreCase(fileName) || fileName.toLowerCase().startsWith("dockerfile.")) {
                    return "dockerfile";
                }
                return "text";
        }
    }

    private String getFileExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0 && dotIndex < fileName.length() - 1) {
            return fileName.substring(dotIndex + 1);
        }
        return "";
    }
}
