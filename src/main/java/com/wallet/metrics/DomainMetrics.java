package com.wallet.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class DomainMetrics {

    private final Counter transfersCompleted;
    private final Counter transfersDeclined;
    private final Counter idempotentReplays;
    private final Counter walletsCreated;

    public DomainMetrics(MeterRegistry registry) {
        this.transfersCompleted = Counter.builder("wallet.transfers.completed")
                .description("Number of completed transfers")
                .register(registry);
        this.transfersDeclined = Counter.builder("wallet.transfers.declined")
                .description("Number of declined transfers (insufficient funds)")
                .register(registry);
        this.idempotentReplays = Counter.builder("wallet.idempotent.replays")
                .description("Number of idempotent replay hits")
                .register(registry);
        this.walletsCreated = Counter.builder("wallet.wallets.created")
                .description("Number of wallets created")
                .register(registry);
    }

    public void incrementCompletedTransfers() { transfersCompleted.increment(); }
    public void incrementDeclinedTransfers() { transfersDeclined.increment(); }
    public void incrementIdempotentReplays() { idempotentReplays.increment(); }
    public void incrementWalletsCreated() { walletsCreated.increment(); }
}
