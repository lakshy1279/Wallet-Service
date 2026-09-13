package com.wallet.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateTransferRequest(
        @NotNull UUID from,
        @NotNull UUID to,
        @NotNull @Min(1) Long amountPaise,
        @NotBlank String idempotencyKey
) {
}
