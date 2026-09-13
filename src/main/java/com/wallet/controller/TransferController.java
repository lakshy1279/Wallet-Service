package com.wallet.controller;

import com.wallet.dto.CreateTransferRequest;
import com.wallet.dto.TransferResponse;
import com.wallet.service.TransferService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
public class TransferController {

    private static final Logger log = LoggerFactory.getLogger(TransferController.class);

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping("/transfers")
    public ResponseEntity<TransferResponse> createTransfer(
            @Valid @RequestBody CreateTransferRequest body,
            HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        log.debug("POST /transfers from={} to={} amount={} key={}",
                body.from(), body.to(), body.amountPaise(), body.idempotencyKey());

        TransferResponse response = transferService.executeTransfer(body, userId);

        if ("DECLINED".equals(response.status())) {
            return ResponseEntity.unprocessableEntity().body(response);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/transfers/{id}")
    public ResponseEntity<TransferResponse> getTransfer(@PathVariable UUID id) {
        return ResponseEntity.ok(transferService.getTransfer(id));
    }
}
