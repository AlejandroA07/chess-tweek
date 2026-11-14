package com.chesstweek.gamereview.internal.engine;

import org.springframework.stereotype.Service;

import com.chesstweek.gamereview.EngineHealth;
import com.chesstweek.gamereview.EngineStatus;

@Service
class StockfishHealthService implements EngineHealth {

    private final StockfishEngineAccess engine;

    StockfishHealthService(StockfishEngineAccess engine) {
        this.engine = engine;
    }

    @Override
    public EngineStatus status() {
        return engine.status();
    }

    @Override
    public EngineStatus check() {
        return engine.withReadyEngine((connection, name) -> Boolean.TRUE).status();
    }
}
