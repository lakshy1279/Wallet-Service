package com.wallet.repository;

import com.wallet.model.Transfer;
import com.wallet.model.TransferStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class TransferRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<Transfer> ROW_MAPPER = (rs, rowNum) -> {
        Transfer t = new Transfer();
        t.setId(rs.getObject("id", UUID.class));
        t.setIdempotencyKey(rs.getString("idempotency_key"));
        t.setFromWalletId(rs.getObject("from_wallet_id", UUID.class));
        t.setToWalletId(rs.getObject("to_wallet_id", UUID.class));
        t.setAmountPaise(rs.getLong("amount_paise"));
        t.setStatus(TransferStatus.valueOf(rs.getString("status")));
        t.setDeclineReason(rs.getString("decline_reason"));
        t.setRequestHash(rs.getString("request_hash"));
        t.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        return t;
    };

    public TransferRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<Transfer> findById(UUID id) {
        List<Transfer> result = jdbcTemplate.query(
                "SELECT * FROM transfers WHERE id = ?", ROW_MAPPER, id);
        return result.isEmpty() ? Optional.empty() : Optional.of(result.get(0));
    }

    public Optional<Transfer> findByIdempotencyKey(String key) {
        List<Transfer> result = jdbcTemplate.query(
                "SELECT * FROM transfers WHERE idempotency_key = ?", ROW_MAPPER, key);
        return result.isEmpty() ? Optional.empty() : Optional.of(result.get(0));
    }

    public Transfer insert(Transfer transfer) {
        if (transfer.getId() == null) {
            transfer.setId(UUID.randomUUID());
        }
        List<Transfer> result = jdbcTemplate.query(
                "INSERT INTO transfers (id, idempotency_key, from_wallet_id, to_wallet_id, " +
                        "amount_paise, status, decline_reason, request_hash) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING *",
                ROW_MAPPER,
                transfer.getId(),
                transfer.getIdempotencyKey(),
                transfer.getFromWalletId(),
                transfer.getToWalletId(),
                transfer.getAmountPaise(),
                transfer.getStatus().name(),
                transfer.getDeclineReason(),
                transfer.getRequestHash());
        return result.get(0);
    }
}
