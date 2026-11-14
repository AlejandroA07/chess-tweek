package com.chesstweek.gamereview.internal.persistence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.chesstweek.gamereview.internal.analysis.Evaluation;
import com.chesstweek.gamereview.internal.analysis.MoveRules;
import com.chesstweek.gamereview.internal.analysis.Score;
import com.chesstweek.gamereview.internal.analysis.TwoPassAnalysis;

@Repository
public class JdbcAnalysisVersionStore implements AnalysisVersionStore {

    private static final String INSERT_VERSION = """
            INSERT INTO game_review_analysis_version (
                account_id, lichess_game_id, side, requested_perspective, analyzed_color, profile_id, rules_version,
                phase_source, middlegame_start, endgame_start, move_count, deep_move_count,
                inaccuracies, mistakes, blunders, total_loss, started_at_ms, completed_at_ms
            ) VALUES (
                :accountId, :gameId, :side, :perspective, :color, :profileId, :rulesVersion,
                :phaseSource, :middlegameStart, :endgameStart, :moveCount, :deepMoveCount,
                :inaccuracies, :mistakes, :blunders, :totalLoss, :startedAt, :completedAt
            )
            """;
    private static final String INSERT_MOVE = """
            INSERT INTO game_review_analysis_move (
                version_id, ply, san, uci, fen_before, phase, pass, label, mate_event,
                played_best, best_move, expected_before, expected_after, loss, after_terminal,
                before_lines_json, after_lines_json
            ) VALUES (
                :versionId, :ply, :san, :uci, :fenBefore, :phase, :pass, :label, :mateEvent,
                :playedBest, :bestMove, :expectedBefore, :expectedAfter, :loss, :afterTerminal,
                :beforeLines, :afterLines
            )
            """;

    private final NamedParameterJdbcTemplate namedJdbc;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    public JdbcAnalysisVersionStore(JdbcTemplate jdbc) {
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    @Override
    @Transactional
    public List<Long> saveAll(List<NewVersion> versions) {
        var ids = new ArrayList<Long>(versions.size());
        for (NewVersion version : versions) {
            ids.add(saveVersion(version));
        }
        return List.copyOf(ids);
    }

    private long saveVersion(NewVersion version) {
        List<TwoPassAnalysis.MoveFinding> findings = version.findings();
        var keys = new GeneratedKeyHolder();
        namedJdbc.update(INSERT_VERSION, new MapSqlParameterSource()
                .addValue("accountId", version.accountId())
                .addValue("gameId", version.gameId())
                .addValue("side", lower(version.side()))
                .addValue("perspective", lower(version.requestedPerspective()))
                .addValue("color", version.analyzedColor().value())
                .addValue("profileId", version.profileId())
                .addValue("rulesVersion", version.rulesVersion())
                .addValue("phaseSource", lower(version.phases().source()))
                .addValue("middlegameStart", version.phases().middlegameStart())
                .addValue("endgameStart", version.phases().endgameStart())
                .addValue("moveCount", findings.size())
                .addValue("deepMoveCount", count(findings, f -> f.pass() == TwoPassAnalysis.Pass.DEEP))
                .addValue("inaccuracies", labelled(findings, MoveRules.Label.INACCURACY))
                .addValue("mistakes", labelled(findings, MoveRules.Label.MISTAKE))
                .addValue("blunders", labelled(findings, MoveRules.Label.BLUNDER))
                .addValue("totalLoss", findings.stream().mapToInt(f -> f.verdict().loss()).sum())
                .addValue("startedAt", version.startedAt().toEpochMilli())
                .addValue("completedAt", version.completedAt().toEpochMilli()),
                keys,
                new String[] {"id"});
        long versionId = keys.getKey().longValue();
        var moves = new MapSqlParameterSource[findings.size()];
        for (int i = 0; i < findings.size(); i++) {
            moves[i] = move(versionId, findings.get(i));
        }
        namedJdbc.batchUpdate(INSERT_MOVE, moves);
        return versionId;
    }

    private MapSqlParameterSource move(long versionId, TwoPassAnalysis.MoveFinding finding) {
        MoveRules.Verdict verdict = finding.verdict();
        String afterLines = null;
        String afterTerminal = "none";
        switch (finding.after()) {
            case MoveRules.After.Searched searched -> afterLines = lines(searched.evaluation());
            case MoveRules.After.Finished finished -> afterTerminal = lower(finished.terminal());
        }
        return new MapSqlParameterSource()
                .addValue("versionId", versionId)
                .addValue("ply", finding.move().ply())
                .addValue("san", finding.move().san())
                .addValue("uci", finding.move().uci())
                .addValue("fenBefore", finding.fenBefore())
                .addValue("phase", lower(finding.phase()))
                .addValue("pass", lower(finding.pass()))
                .addValue("label", lower(verdict.label()))
                .addValue("mateEvent", lower(verdict.mateEvent()))
                .addValue("playedBest", verdict.playedBest() ? 1 : 0)
                .addValue("bestMove", finding.before().best().move())
                .addValue("expectedBefore", verdict.before().halfPermille())
                .addValue("expectedAfter", verdict.after().halfPermille())
                .addValue("loss", verdict.loss())
                .addValue("afterTerminal", afterTerminal)
                .addValue("beforeLines", lines(finding.before()))
                .addValue("afterLines", afterLines);
    }

    private String lines(Evaluation evaluation) {
        var lines = new ArrayList<Map<String, Object>>();
        for (Evaluation.Line line : evaluation.lines()) {
            var json = new LinkedHashMap<String, Object>();
            json.put("move", line.move());
            switch (line.score()) {
                case Score.Centipawns centipawns -> json.put("cp", centipawns.value());
                case Score.Mate mate -> json.put("mate", mate.moves());
            }
            json.put("wdl", List.of(line.wdl().win(), line.wdl().draw(), line.wdl().loss()));
            json.put("pv", line.pv());
            lines.add(json);
        }
        return objectMapper.writeValueAsString(lines);
    }

    private static int labelled(List<TwoPassAnalysis.MoveFinding> findings, MoveRules.Label label) {
        return count(findings, finding -> finding.verdict().label() == label);
    }

    private static int count(
            List<TwoPassAnalysis.MoveFinding> findings,
            java.util.function.Predicate<TwoPassAnalysis.MoveFinding> matches) {
        return (int) findings.stream().filter(matches).count();
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
