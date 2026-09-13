package com.wallet.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record DepositRequest(
        @NotNull @Min(1) Long amountPaise
) {
}
