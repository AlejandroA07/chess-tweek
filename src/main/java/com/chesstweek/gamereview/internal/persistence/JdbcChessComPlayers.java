package com.chesstweek.gamereview.internal.persistence;

import java.time.Clock;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.chesstweek.gamereview.ChessComPlayers;

@Repository
public class JdbcChessComPlayers implements ChessComPlayers {

    private static final Pattern ACCOUNT_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public JdbcChessComPlayers(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    JdbcChessComPlayers(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public Optional<String> username(String accountId) {
        requireAccountId(accountId);
        return jdbc.queryForList(
                        "SELECT username FROM game_review_chesscom_player WHERE account_id = ?",
                        String.class,
                        accountId)
                .stream()
                .findFirst();
    }

    @Override
    public void save(String accountId, String username) {
        requireAccountId(accountId);
        if (!ChessComPlayers.isValidUsername(username)) {
            throw new IllegalArgumentException("Invalid chess.com username");
        }
        jdbc.update("""
                INSERT INTO game_review_chesscom_player (account_id, username, saved_at_ms)
                VALUES (?, ?, ?)
                ON CONFLICT (account_id) DO UPDATE SET
                    username = excluded.username,
                    saved_at_ms = excluded.saved_at_ms
                """, accountId, username, clock.millis());
    }

    @Override
    public void remove(String accountId) {
        requireAccountId(accountId);
        jdbc.update("DELETE FROM game_review_chesscom_player WHERE account_id = ?", accountId);
    }

    private static void requireAccountId(String accountId) {
        if (accountId == null || !ACCOUNT_ID.matcher(accountId).matches()) {
            throw new IllegalArgumentException("Invalid account id");
        }
    }
}
