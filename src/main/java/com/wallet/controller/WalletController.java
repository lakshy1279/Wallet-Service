package com.wallet.controller;

import com.wallet.dto.DepositRequest;
import com.wallet.dto.WalletResponse;
import com.wallet.service.WalletService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
public class WalletController {

    private static final Logger log = LoggerFactory.getLogger(WalletController.class);

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @PostMapping("/wallets")
    public ResponseEntity<WalletResponse> createWallet(HttpServletRequest request) {
        String userId = getUserId(request);
        log.debug("POST /wallets for user={}", userId);
        WalletResponse wallet = walletService.getOrCreateWallet(userId);
        return ResponseEntity.ok(wallet);
    }

    @GetMapping("/wallets/{id}")
    public ResponseEntity<WalletResponse> getWallet(@PathVariable UUID id) {
        return ResponseEntity.ok(walletService.getWallet(id));
    }

    @PostMapping("/wallets/{id}/deposit")
    public ResponseEntity<WalletResponse> deposit(@PathVariable UUID id,
                                                   @Valid @RequestBody DepositRequest body,
                                                   HttpServletRequest request) {
        String userId = getUserId(request);
        log.debug("POST /wallets/{}/deposit amount={}", id, body.amountPaise());
        WalletResponse wallet = walletService.deposit(id, body.amountPaise(), userId);
        return ResponseEntity.ok(wallet);
    }

    private String getUserId(HttpServletRequest request) {
        return (String) request.getAttribute("userId");
    }
}
