package com.wallet.service;

import com.wallet.dto.WalletResponse;
import com.wallet.exception.UnauthorizedAccessException;
import com.wallet.exception.WalletNotFoundException;
import com.wallet.metrics.DomainMetrics;
import com.wallet.model.Wallet;
import com.wallet.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    private final WalletRepository walletRepository;
    private final DomainMetrics domainMetrics;

    public WalletService(WalletRepository walletRepository, DomainMetrics domainMetrics) {
        this.walletRepository = walletRepository;
        this.domainMetrics = domainMetrics;
    }

    public WalletResponse getOrCreateWallet(String userId) {
        // Check if wallet exists first
        boolean existed = walletRepository.findByUserId(userId).isPresent();
        Wallet wallet = walletRepository.insertOrGet(userId);

        if (!existed) {
            log.info("event=wallet_created user_id={} wallet_id={}", userId, wallet.getId());
            domainMetrics.incrementWalletsCreated();
        } else {
            log.info("event=wallet_found user_id={} wallet_id={}", userId, wallet.getId());
        }

        return toResponse(wallet);
    }

    public WalletResponse getWallet(UUID id) {
        Wallet wallet = walletRepository.findById(id)
                .orElseThrow(() -> new WalletNotFoundException(id));
        return toResponse(wallet);
    }

    @Transactional
    public WalletResponse deposit(UUID walletId, long amountPaise, String userId) {
        Wallet wallet = walletRepository.findByIdForUpdate(walletId)
                .orElseThrow(() -> new WalletNotFoundException(walletId));

        if (!wallet.getUserId().equals(userId)) {
            throw new UnauthorizedAccessException("You don't own this wallet");
        }

        long newBalance = wallet.getBalancePaise() + amountPaise;
        walletRepository.updateBalance(walletId, newBalance);
        wallet.setBalancePaise(newBalance);

        log.info("event=deposit wallet_id={} amount_paise={} new_balance={}", walletId, amountPaise, newBalance);
        return toResponse(wallet);
    }

    private WalletResponse toResponse(Wallet w) {
        return new WalletResponse(w.getId(), w.getUserId(), w.getBalancePaise(), w.getCreatedAt());
    }
}
