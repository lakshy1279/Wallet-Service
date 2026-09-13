package com.wallet.dto;

import java.time.Instant;
import java.util.UUID;

public record WalletResponse(
        UUID id,
        String userId,
        long balancePaise,
        Instant createdAt
) {
}
