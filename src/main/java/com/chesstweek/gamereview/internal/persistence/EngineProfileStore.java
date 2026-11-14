package com.chesstweek.gamereview.internal.persistence;

import java.util.Optional;

import com.chesstweek.gamereview.EngineProfile;

public interface EngineProfileStore {

    /** Stores a new immutable profile and returns it with its assigned id. */
    EngineProfile save(EngineProfile profile);

    Optional<EngineProfile> latest();
}
