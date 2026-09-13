package com.wallet.model;

import java.time.Instant;
import java.util.UUID;

public class Wallet {
    private UUID id;
    private String userId;
    private long balancePaise;
    private Instant createdAt;
    private Instant updatedAt;

    public Wallet() {
    }

    public Wallet(UUID id, String userId, long balancePaise, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.userId = userId;
        this.balancePaise = balancePaise;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public long getBalancePaise() { return balancePaise; }
    public void setBalancePaise(long balancePaise) { this.balancePaise = balancePaise; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
