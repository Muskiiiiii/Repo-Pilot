package com.repopilot.llm.dto;

public class AuditExportEncryptionResult {
    private byte[] ciphertext;
    private byte[] encryptedDek;
    private byte[] nonce;
    private int keyVersion;
    private String plaintextHash;
    private String ciphertextHash;
    private String aad;

    public AuditExportEncryptionResult() {
    }

    public AuditExportEncryptionResult(byte[] ciphertext, byte[] encryptedDek, byte[] nonce, int keyVersion,
                                       String plaintextHash, String ciphertextHash, String aad) {
        this.ciphertext = ciphertext;
        this.encryptedDek = encryptedDek;
        this.nonce = nonce;
        this.keyVersion = keyVersion;
        this.plaintextHash = plaintextHash;
        this.ciphertextHash = ciphertextHash;
        this.aad = aad;
    }

    public byte[] getCiphertext() {
        return ciphertext;
    }

    public void setCiphertext(byte[] ciphertext) {
        this.ciphertext = ciphertext;
    }

    public byte[] getEncryptedDek() {
        return encryptedDek;
    }

    public void setEncryptedDek(byte[] encryptedDek) {
        this.encryptedDek = encryptedDek;
    }

    public byte[] getNonce() {
        return nonce;
    }

    public void setNonce(byte[] nonce) {
        this.nonce = nonce;
    }

    public int getKeyVersion() {
        return keyVersion;
    }

    public void setKeyVersion(int keyVersion) {
        this.keyVersion = keyVersion;
    }

    public String getPlaintextHash() {
        return plaintextHash;
    }

    public void setPlaintextHash(String plaintextHash) {
        this.plaintextHash = plaintextHash;
    }

    public String getCiphertextHash() {
        return ciphertextHash;
    }

    public void setCiphertextHash(String ciphertextHash) {
        this.ciphertextHash = ciphertextHash;
    }

    public String getAad() {
        return aad;
    }

    public void setAad(String aad) {
        this.aad = aad;
    }
}
