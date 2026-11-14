package com.chesstweek.gamereview.internal.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import com.chesstweek.gamereview.EngineProfile;

@Repository
public class JdbcEngineProfileStore implements EngineProfileStore {

    private static final String INSERT = """
            INSERT INTO game_review_engine_profile (
                engine_name, threads, hash_mb, multi_pv, benchmark_positions,
                benchmark_nodes_per_position, nodes_per_second, quick_target_ms, deep_target_ms,
                quick_nodes, deep_nodes, created_at_ms
            ) VALUES (
                :engineName, :threads, :hashMb, :multiPv, :benchmarkPositions,
                :benchmarkNodes, :nodesPerSecond, :quickTargetMs, :deepTargetMs,
                :quickNodes, :deepNodes, :createdAt
            )
            """;
    private static final String LATEST = """
            SELECT *
            FROM game_review_engine_profile
            ORDER BY id DESC
            LIMIT 1
            """;

    private final NamedParameterJdbcTemplate namedJdbc;

    public JdbcEngineProfileStore(JdbcTemplate jdbc) {
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    @Override
    public EngineProfile save(EngineProfile profile) {
        var keys = new GeneratedKeyHolder();
        namedJdbc.update(INSERT, new MapSqlParameterSource()
                .addValue("engineName", profile.engineName())
                .addValue("threads", profile.threads())
                .addValue("hashMb", profile.hashMb())
                .addValue("multiPv", profile.multiPv())
                .addValue("benchmarkPositions", profile.benchmarkPositions())
                .addValue("benchmarkNodes", profile.benchmarkNodesPerPosition())
                .addValue("nodesPerSecond", profile.nodesPerSecond())
                .addValue("quickTargetMs", profile.quickTargetMs())
                .addValue("deepTargetMs", profile.deepTargetMs())
                .addValue("quickNodes", profile.quickNodes())
                .addValue("deepNodes", profile.deepNodes())
                .addValue("createdAt", profile.createdAt().toEpochMilli()),
                keys,
                new String[] {"id"});
        return profile.withId(keys.getKey().longValue());
    }

    @Override
    public Optional<EngineProfile> latest() {
        return namedJdbc.query(LATEST, new MapSqlParameterSource(), JdbcEngineProfileStore::map)
                .stream()
                .findFirst();
    }

    private static EngineProfile map(ResultSet row, int rowNumber) throws SQLException {
        return new EngineProfile(
                row.getLong("id"),
                row.getString("engine_name"),
                row.getInt("threads"),
                row.getInt("hash_mb"),
                row.getInt("multi_pv"),
                row.getInt("benchmark_positions"),
                row.getLong("benchmark_nodes_per_position"),
                row.getLong("nodes_per_second"),
                row.getInt("quick_target_ms"),
                row.getInt("deep_target_ms"),
                row.getLong("quick_nodes"),
                row.getLong("deep_nodes"),
                Instant.ofEpochMilli(row.getLong("created_at_ms")));
    }
}
