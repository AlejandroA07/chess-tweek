package com.chesstweek.training.internal.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcOpeningNoteStore implements OpeningNoteStore {

    private static final Pattern ACCOUNT_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private final JdbcTemplate jdbc;

    public JdbcOpeningNoteStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StoredNote> find(String accountId, String positionKey) {
        checkAccount(accountId);
        return jdbc.query("""
                        SELECT position_key, fen, note_text, updated_at_ms FROM training_opening_note
                        WHERE account_id = ? AND position_key = ?
                        """,
                        JdbcOpeningNoteStore::note,
                        accountId,
                        positionKey)
                .stream()
                .findFirst();
    }

    @Override
    @Transactional
    public void put(String accountId, String positionKey, String fen, String text, Instant updatedAt) {
        checkAccount(accountId);
        jdbc.update("""
                INSERT INTO training_opening_note (account_id, position_key, fen, note_text, updated_at_ms)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (account_id, position_key) DO UPDATE SET
                    fen = excluded.fen,
                    note_text = excluded.note_text,
                    updated_at_ms = excluded.updated_at_ms
                """,
                accountId, positionKey, fen, text, updatedAt.toEpochMilli());
    }

    @Override
    @Transactional
    public void delete(String accountId, String positionKey) {
        checkAccount(accountId);
        jdbc.update(
                "DELETE FROM training_opening_note WHERE account_id = ? AND position_key = ?",
                accountId,
                positionKey);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoredNote> all(String accountId) {
        checkAccount(accountId);
        return jdbc.query("""
                        SELECT position_key, fen, note_text, updated_at_ms FROM training_opening_note
                        WHERE account_id = ?
                        ORDER BY updated_at_ms, position_key
                        """,
                        JdbcOpeningNoteStore::note,
                        accountId);
    }

    private static StoredNote note(ResultSet row, int index) throws SQLException {
        return new StoredNote(
                row.getString("position_key"),
                row.getString("fen"),
                row.getString("note_text"),
                Instant.ofEpochMilli(row.getLong("updated_at_ms")));
    }

    private static void checkAccount(String accountId) {
        if (accountId == null || !ACCOUNT_ID.matcher(accountId).matches()) {
            throw new IllegalArgumentException("Invalid account");
        }
    }
}
