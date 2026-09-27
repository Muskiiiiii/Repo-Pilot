package com.repopilot.testing.policy;

import com.repopilot.testing.entity.FailureType;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class FailureFingerprintResult {

    private final String fingerprint;
    private final FailureType failureCategory;
    private final String parserStatus;
    private final String normalizedFailureIdentity;
    private final boolean isRepeated;
    private final boolean isOscillating;
    private final List<String> historicalFingerprints;

    public FailureFingerprintResult(String fingerprint,
                                    FailureType failureCategory,
                                    String parserStatus,
                                    String normalizedFailureIdentity,
                                    boolean isRepeated,
                                    boolean isOscillating,
                                    List<String> historicalFingerprints) {
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint cannot be null");
        this.failureCategory = failureCategory != null ? failureCategory : FailureType.UNKNOWN;
        this.parserStatus = parserStatus != null ? parserStatus : "UNPARSED";
        this.normalizedFailureIdentity = normalizedFailureIdentity != null ? normalizedFailureIdentity : "";
        this.isRepeated = isRepeated;
        this.isOscillating = isOscillating;
        this.historicalFingerprints = historicalFingerprints != null ? List.copyOf(historicalFingerprints) : Collections.emptyList();
    }

    public String getFingerprint() { return fingerprint; }
    public FailureType getFailureCategory() { return failureCategory; }
    public String getParserStatus() { return parserStatus; }
    public String getNormalizedFailureIdentity() { return normalizedFailureIdentity; }
    public boolean isRepeated() { return isRepeated; }
    public boolean isOscillating() { return isOscillating; }
    public List<String> getHistoricalFingerprints() { return historicalFingerprints; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FailureFingerprintResult that = (FailureFingerprintResult) o;
        return isRepeated == that.isRepeated &&
                isOscillating == that.isOscillating &&
                Objects.equals(fingerprint, that.fingerprint) &&
                failureCategory == that.failureCategory &&
                Objects.equals(parserStatus, that.parserStatus) &&
                Objects.equals(normalizedFailureIdentity, that.normalizedFailureIdentity) &&
                Objects.equals(historicalFingerprints, that.historicalFingerprints);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fingerprint, failureCategory, parserStatus, normalizedFailureIdentity, isRepeated, isOscillating, historicalFingerprints);
    }

    @Override
    public String toString() {
        return "FailureFingerprintResult{" +
                "fingerprint='" + fingerprint + '\'' +
                ", failureCategory=" + failureCategory +
                ", parserStatus='" + parserStatus + '\'' +
                ", normalizedFailureIdentity='" + normalizedFailureIdentity + '\'' +
                ", isRepeated=" + isRepeated +
                ", isOscillating=" + isOscillating +
                ", historicalFingerprints=" + historicalFingerprints +
                '}';
    }
}
