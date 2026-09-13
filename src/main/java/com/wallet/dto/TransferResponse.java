package com.wallet.dto;

import java.time.Instant;
import java.util.UUID;

public record TransferResponse(
        UUID id,
        String idempotencyKey,
        UUID fromWalletId,
        UUID toWalletId,
        long amountPaise,
        String status,
        String declineReason,
        Instant createdAt
) {
}
