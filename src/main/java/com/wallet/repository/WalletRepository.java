package com.wallet.repository;

import com.wallet.model.Wallet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class WalletRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<Wallet> ROW_MAPPER = (rs, rowNum) -> {
        Wallet w = new Wallet();
        w.setId(rs.getObject("id", UUID.class));
        w.setUserId(rs.getString("user_id"));
        w.setBalancePaise(rs.getLong("balance_paise"));
        w.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        w.setUpdatedAt(rs.getTimestamp("updated_at").toInstant());
        return w;
    };

    public WalletRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<Wallet> findById(UUID id) {
        List<Wallet> result = jdbcTemplate.query(
                "SELECT * FROM wallets WHERE id = ?", ROW_MAPPER, id);
        return result.isEmpty() ? Optional.empty() : Optional.of(result.get(0));
    }

    public Optional<Wallet> findByUserId(String userId) {
        List<Wallet> result = jdbcTemplate.query(
                "SELECT * FROM wallets WHERE user_id = ?", ROW_MAPPER, userId);
        return result.isEmpty() ? Optional.empty() : Optional.of(result.get(0));
    }

    /**
     * Atomic get-or-create. Uses INSERT ... ON CONFLICT DO NOTHING.
     * If the row already exists (conflict), the RETURNING clause returns nothing,
     * so we fall back to a SELECT.
     */
    public Wallet insertOrGet(String userId) {
        List<Wallet> result = jdbcTemplate.query(
                "INSERT INTO wallets (user_id) VALUES (?) ON CONFLICT (user_id) DO NOTHING RETURNING *",
                ROW_MAPPER, userId);
        if (!result.isEmpty()) {
            return result.get(0);
        }
        // Conflict occurred — wallet already exists
        return jdbcTemplate.queryForObject(
                "SELECT * FROM wallets WHERE user_id = ?", ROW_MAPPER, userId);
    }

    public Optional<Wallet> findByIdForUpdate(UUID id) {
        List<Wallet> result = jdbcTemplate.query(
                "SELECT * FROM wallets WHERE id = ? FOR UPDATE", ROW_MAPPER, id);
        return result.isEmpty() ? Optional.empty() : Optional.of(result.get(0));
    }

    /**
     * Lock two wallet rows in sorted order to prevent deadlocks.
     * The caller MUST pass ids in sorted order (UUID natural ordering).
     */
    public List<Wallet> findAllByIdsForUpdate(UUID id1, UUID id2) {
        return jdbcTemplate.query(
                "SELECT * FROM wallets WHERE id IN (?, ?) ORDER BY id FOR UPDATE",
                ROW_MAPPER, id1, id2);
    }

    public void updateBalance(UUID id, long newBalance) {
        jdbcTemplate.update(
                "UPDATE wallets SET balance_paise = ?, updated_at = now() WHERE id = ?",
                newBalance, id);
    }

    public long sumAllBalances() {
        Long sum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(balance_paise), 0) FROM wallets", Long.class);
        return sum != null ? sum : 0L;
    }
}
