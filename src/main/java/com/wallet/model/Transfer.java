package com.wallet.model;

import java.time.Instant;
import java.util.UUID;

public class Transfer {
    private UUID id;
    private String idempotencyKey;
    private UUID fromWalletId;
    private UUID toWalletId;
    private long amountPaise;
    private TransferStatus status;
    private String declineReason;
    private String requestHash;
    private Instant createdAt;

    public Transfer() {
    }

    public Transfer(UUID id, String idempotencyKey, UUID fromWalletId, UUID toWalletId,
                    long amountPaise, TransferStatus status, String declineReason,
                    String requestHash, Instant createdAt) {
        this.id = id;
        this.idempotencyKey = idempotencyKey;
        this.fromWalletId = fromWalletId;
        this.toWalletId = toWalletId;
        this.amountPaise = amountPaise;
        this.status = status;
        this.declineReason = declineReason;
        this.requestHash = requestHash;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public UUID getFromWalletId() { return fromWalletId; }
    public void setFromWalletId(UUID fromWalletId) { this.fromWalletId = fromWalletId; }
    public UUID getToWalletId() { return toWalletId; }
    public void setToWalletId(UUID toWalletId) { this.toWalletId = toWalletId; }
    public long getAmountPaise() { return amountPaise; }
    public void setAmountPaise(long amountPaise) { this.amountPaise = amountPaise; }
    public TransferStatus getStatus() { return status; }
    public void setStatus(TransferStatus status) { this.status = status; }
    public String getDeclineReason() { return declineReason; }
    public void setDeclineReason(String declineReason) { this.declineReason = declineReason; }
    public String getRequestHash() { return requestHash; }
    public void setRequestHash(String requestHash) { this.requestHash = requestHash; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
