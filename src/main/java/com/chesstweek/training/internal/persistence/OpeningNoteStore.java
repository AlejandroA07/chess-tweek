package com.chesstweek.training.internal.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** The owner's opening notes, keyed by account and explorer position key. */
public interface OpeningNoteStore {

    Optional<StoredNote> find(String accountId, String positionKey);

    void put(String accountId, String positionKey, String fen, String text, Instant updatedAt);

    void delete(String accountId, String positionKey);

    /** Every stored note of the account, oldest first. */
    List<StoredNote> all(String accountId);

    record StoredNote(String positionKey, String fen, String text, Instant updatedAt) {}
}
