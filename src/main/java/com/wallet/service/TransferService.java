package com.wallet.service;

import com.wallet.dto.CreateTransferRequest;
import com.wallet.dto.TransferResponse;
import com.wallet.exception.IdempotencyConflictException;
import com.wallet.exception.UnauthorizedAccessException;
import com.wallet.exception.WalletNotFoundException;
import com.wallet.metrics.DomainMetrics;
import com.wallet.model.Transfer;
import com.wallet.model.TransferStatus;
import com.wallet.model.Wallet;
import com.wallet.repository.TransferRepository;
import com.wallet.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final WalletRepository walletRepository;
    private final TransferRepository transferRepository;
    private final TransactionTemplate transactionTemplate;
    private final DomainMetrics domainMetrics;

    public TransferService(WalletRepository walletRepository,
                           TransferRepository transferRepository,
                           TransactionTemplate transactionTemplate,
                           DomainMetrics domainMetrics) {
        this.walletRepository = walletRepository;
        this.transferRepository = transferRepository;
        this.transactionTemplate = transactionTemplate;
        this.domainMetrics = domainMetrics;
    }

    /**
     * Execute a P2P transfer with:
     * - Sorted-order wallet locking (deadlock prevention)
     * - Balance check (no overdraft)
     * - Idempotency (same-txn insert with UNIQUE constraint)
     * - Conservation (debit + credit + record in one transaction)
     */
    public TransferResponse executeTransfer(CreateTransferRequest request, String authenticatedUserId) {
        String requestHash = computeHash(request.from(), request.to(), request.amountPaise());

        // 1. Pre-check idempotency (optimization — real enforcement is the UNIQUE constraint)
        Optional<Transfer> existing = transferRepository.findByIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) {
            return handleExistingTransfer(existing.get(), requestHash);
        }

        // 2. Execute in a single transaction
        try {
            return transactionTemplate.execute(status -> {
                // a. Sort wallet IDs → prevents deadlock when A→B and B→A run concurrently
                UUID firstId, secondId;
                if (request.from().compareTo(request.to()) < 0) {
                    firstId = request.from();
                    secondId = request.to();
                } else {
                    firstId = request.to();
                    secondId = request.from();
                }

                // b. Lock both wallets in sorted order
                List<Wallet> lockedWallets = walletRepository.findAllByIdsForUpdate(firstId, secondId);
                if (lockedWallets.size() != 2) {
                    throw new WalletNotFoundException("One or both wallets not found");
                }

                Wallet fromWallet = lockedWallets.stream()
                        .filter(w -> w.getId().equals(request.from())).findFirst()
                        .orElseThrow(() -> new WalletNotFoundException(request.from()));
                Wallet toWallet = lockedWallets.stream()
                        .filter(w -> w.getId().equals(request.to())).findFirst()
                        .orElseThrow(() -> new WalletNotFoundException(request.to()));

                // c. Verify caller owns the source wallet
                if (!fromWallet.getUserId().equals(authenticatedUserId)) {
                    throw new UnauthorizedAccessException("Not authorized to debit this wallet");
                }

                // d. Check balance — decline if insufficient
                if (fromWallet.getBalancePaise() < request.amountPaise()) {
                    Transfer declined = createTransfer(request, requestHash,
                            TransferStatus.DECLINED, "Insufficient funds");
                    Transfer saved = transferRepository.insert(declined);

                    log.warn("event=transfer_declined wallet_id={} balance={} requested={}",
                            request.from(), fromWallet.getBalancePaise(), request.amountPaise());
                    domainMetrics.incrementDeclinedTransfers();
                    return toResponse(saved);
                }

                // e. Debit sender, credit receiver
                walletRepository.updateBalance(fromWallet.getId(),
                        fromWallet.getBalancePaise() - request.amountPaise());
                walletRepository.updateBalance(toWallet.getId(),
                        toWallet.getBalancePaise() + request.amountPaise());

                // f. Insert transfer record — same transaction as balance changes
                Transfer transfer = createTransfer(request, requestHash,
                        TransferStatus.COMPLETED, null);
                Transfer saved = transferRepository.insert(transfer);

                log.info("event=transfer_completed transfer_id={} from={} to={} amount_paise={}",
                        saved.getId(), request.from(), request.to(), request.amountPaise());
                domainMetrics.incrementCompletedTransfers();

                return toResponse(saved);
            });
        } catch (DuplicateKeyException e) {
            // Race: concurrent request with same idempotency_key won the insert
            log.info("event=idempotent_race_resolved key={}", request.idempotencyKey());
            Transfer winner = transferRepository.findByIdempotencyKey(request.idempotencyKey())
                    .orElseThrow(() -> new RuntimeException(
                            "Transfer disappeared after duplicate key conflict"));
            return handleExistingTransfer(winner, requestHash);
        }
    }

    public TransferResponse getTransfer(UUID id) {
        Transfer transfer = transferRepository.findById(id)
                .orElseThrow(() -> new WalletNotFoundException("Transfer not found: " + id));
        return toResponse(transfer);
    }

    // ── Private helpers ──────────────────────────────────────────

    private TransferResponse handleExistingTransfer(Transfer existing, String requestHash) {
        if (!existing.getRequestHash().equals(requestHash)) {
            throw new IdempotencyConflictException(
                    "Idempotency key already used with different request body");
        }
        log.info("event=idempotent_replay key={} transfer_id={}",
                existing.getIdempotencyKey(), existing.getId());
        domainMetrics.incrementIdempotentReplays();
        return toResponse(existing);
    }

    private Transfer createTransfer(CreateTransferRequest req, String hash,
                                    TransferStatus status, String declineReason) {
        Transfer t = new Transfer();
        t.setId(UUID.randomUUID());
        t.setIdempotencyKey(req.idempotencyKey());
        t.setFromWalletId(req.from());
        t.setToWalletId(req.to());
        t.setAmountPaise(req.amountPaise());
        t.setStatus(status);
        t.setDeclineReason(declineReason);
        t.setRequestHash(hash);
        t.setCreatedAt(Instant.now());
        return t;
    }

    private String computeHash(UUID from, UUID to, long amountPaise) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String input = from + ":" + to + ":" + amountPaise;
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private TransferResponse toResponse(Transfer t) {
        return new TransferResponse(
                t.getId(),
                t.getIdempotencyKey(),
                t.getFromWalletId(),
                t.getToWalletId(),
                t.getAmountPaise(),
                t.getStatus().name(),
                t.getDeclineReason(),
                t.getCreatedAt()
        );
    }
}
