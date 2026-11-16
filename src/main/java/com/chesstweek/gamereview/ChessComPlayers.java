package com.chesstweek.gamereview;

import java.util.Optional;
import java.util.regex.Pattern;

/** The chess.com username stored for each account. */
public interface ChessComPlayers {

    Pattern USERNAME = Pattern.compile("[A-Za-z0-9_-]{3,25}");

    Optional<String> username(String accountId);

    /** Stores or replaces the account's username; it must match {@link #USERNAME}. */
    void save(String accountId, String username);

    void remove(String accountId);

    static boolean isValidUsername(String username) {
        return username != null && USERNAME.matcher(username).matches();
    }
}
