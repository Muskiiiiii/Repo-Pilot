package com.repopilot.change.service;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

@Service
public class DiffGeneratorService {

    public String computeSha256Hash(String content) {
        if (content == null) return null;
        String canonical = content.replace("\r\n", "\n");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    public String generateUnifiedDiff(String filePath, String oldContent, String newContent) {
        String cleanPath = filePath != null ? filePath.replaceAll("^/+", "") : "file";
        String oldHeader = "--- a/" + cleanPath;
        String newHeader = "+++ b/" + cleanPath;

        String safeOld = oldContent != null ? oldContent.replace("\r\n", "\n") : "";
        String safeNew = newContent != null ? newContent.replace("\r\n", "\n") : "";

        String[] oldLines = safeOld.isEmpty() ? new String[0] : safeOld.split("\n", -1);
        String[] newLines = safeNew.isEmpty() ? new String[0] : safeNew.split("\n", -1);

        StringBuilder diffBuilder = new StringBuilder();
        diffBuilder.append(oldHeader).append("\n");
        diffBuilder.append(newHeader).append("\n");

        if (oldLines.length == 0 && newLines.length == 0) {
            return diffBuilder.toString();
        }

        diffBuilder.append(String.format("@@ -%d,%d +%d,%d @@\n",
                oldLines.length > 0 ? 1 : 0, oldLines.length,
                newLines.length > 0 ? 1 : 0, newLines.length));

        // Use Myers diff or line-by-line diff
        List<String> diffLines = computeLineDiff(oldLines, newLines);
        for (String line : diffLines) {
            diffBuilder.append(line).append("\n");
        }

        return diffBuilder.toString().trim();
    }

    public boolean validateDiffApplication(String oldContent, String newContent, String diff) {
        if (diff == null || diff.isBlank()) {
            return (oldContent == null ? "" : oldContent.replace("\r\n", "\n"))
                    .equals(newContent == null ? "" : newContent.replace("\r\n", "\n"));
        }

        String safeOld = oldContent != null ? oldContent.replace("\r\n", "\n") : "";
        String safeNew = newContent != null ? newContent.replace("\r\n", "\n") : "";

        String[] oldLines = safeOld.isEmpty() ? new String[0] : safeOld.split("\n", -1);
        String[] diffRows = diff.split("\n");

        List<String> reconstructedNewLines = new ArrayList<>();
        int oldIndex = 0;

        for (String row : diffRows) {
            if (row.startsWith("---") || row.startsWith("+++") || row.startsWith("@@")) {
                continue;
            }
            if (row.startsWith("+")) {
                reconstructedNewLines.add(row.substring(1));
            } else if (row.startsWith("-")) {
                String deletedLine = row.substring(1);
                if (oldIndex >= oldLines.length || !oldLines[oldIndex].equals(deletedLine)) {
                    return false; // Deleted line does not match original content!
                }
                oldIndex++;
            } else if (row.startsWith(" ")) {
                String contextLine = row.substring(1);
                if (oldIndex >= oldLines.length || !oldLines[oldIndex].equals(contextLine)) {
                    return false; // Context line does not match original content!
                }
                reconstructedNewLines.add(contextLine);
                oldIndex++;
            }
        }

        // Verify all old lines were consumed
        if (oldIndex != oldLines.length) {
            return false;
        }

        String reconstructed = String.join("\n", reconstructedNewLines);
        return reconstructed.equals(safeNew);
    }

    public static final int MAX_DIFF_LINES = 10000;
    public static final int MAX_DIFF_CHARS = 500000;

    private List<String> computeLineDiff(String[] oldLines, String[] newLines) {
        int oldLen = oldLines.length;
        int newLen = newLines.length;

        // Resource Guard: Check if line count or total char count exceeds thresholds
        long totalChars = 0;
        for (String l : oldLines) totalChars += l.length();
        for (String l : newLines) totalChars += l.length();

        if (oldLen > MAX_DIFF_LINES || newLen > MAX_DIFF_LINES || totalChars > MAX_DIFF_CHARS) {
            List<String> fallback = new ArrayList<>(oldLen + newLen);
            for (String l : oldLines) {
                fallback.add("-" + l);
            }
            for (String l : newLines) {
                fallback.add("+" + l);
            }
            return fallback;
        }

        List<String> result = new ArrayList<>();

        // Simple longest common subsequence (LCS) based diff line generator
        int[][] lcs = new int[oldLen + 1][newLen + 1];
        for (int i = 1; i <= oldLen; i++) {
            for (int j = 1; j <= newLen; j++) {
                if (oldLines[i - 1].equals(newLines[j - 1])) {
                    lcs[i][j] = lcs[i - 1][j - 1] + 1;
                } else {
                    lcs[i][j] = Math.max(lcs[i - 1][j], lcs[i][j - 1]);
                }
            }
        }

        List<String> revLines = new ArrayList<>();
        int i = oldLen, j = newLen;
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && oldLines[i - 1].equals(newLines[j - 1])) {
                revLines.add(" " + oldLines[i - 1]);
                i--;
                j--;
            } else if (j > 0 && (i == 0 || lcs[i][j - 1] >= lcs[i - 1][j])) {
                revLines.add("+" + newLines[j - 1]);
                j--;
            } else if (i > 0 && (j == 0 || lcs[i][j - 1] < lcs[i - 1][j])) {
                revLines.add("-" + oldLines[i - 1]);
                i--;
            }
        }

        for (int k = revLines.size() - 1; k >= 0; k--) {
            result.add(revLines.get(k));
        }

        return result;
    }
}
