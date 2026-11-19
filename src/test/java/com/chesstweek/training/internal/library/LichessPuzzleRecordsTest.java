package com.chesstweek.training.internal.library;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Record rules pinned to shapes observed in the official 2026-11-01 export. */
class LichessPuzzleRecordsTest {

    @Test
    void acceptsARepeatedMoveAndHyphenatedOpeningTags() {
        PuzzleImportBatch.PuzzleRow row = LichessPuzzleRecords.validate(LichessPuzzleRecords.parse(
                "006E1,5rk1/R4pp1/1p5p/3Q4/1PPp2q1/3P2P1/5P2/4nK2 w - - 0 34,"
                        + "f1e1 f8e8 e1f1 g4h3 d5g2 e8e1 f1e1 h3g2,1543,73,96,21487,"
                        + "crushing deflection endgame veryLong,https://lichess.org/game/KaLKLKD5#67,"
                        + "Caro-Kann_Defense Caro-Kann_Defense_Advance_Variation,"));

        assertThat(row.setupMove()).isEqualTo("f1e1");
        assertThat(row.solutionMoves()).containsExactly(
                "f8e8", "e1f1", "g4h3", "d5g2", "e8e1", "f1e1", "h3g2");
        assertThat(row.openingTags()).containsExactly(
                "Caro-Kann_Defense", "Caro-Kann_Defense_Advance_Variation");
    }

    @Test
    void acceptsTheRatingFloorAndAnEpochMillisecondDailyDate() {
        PuzzleImportBatch.PuzzleRow floor = LichessPuzzleRecords.validate(LichessPuzzleRecords.parse(
                "00JET,3r2k1/5ppp/3Rp3/8/8/8/1P3PPP/2R3K1 b - - 0 34,d8d6 c1c8 d6d8 c8d8,399,101,82,"
                        + "227,backRankMate endgame mate mateIn2 rookEndgame short,"
                        + "https://lichess.org/game/O8W3yIa7/black#68,,"));
        PuzzleImportBatch.PuzzleRow daily = LichessPuzzleRecords.validate(LichessPuzzleRecords.parse(
                "00sBK,3r2k1/p4p1p/1p3qp1/3B1b2/8/1Qr1PP1P/P2R1KP1/7R w - - 0 23,"
                        + "d5f7 g8g7 b3c3 f6c3,1829,75,85,83,advantage defensiveMove middlegame short,"
                        + "https://lichess.org/game/nMNDAjGq#45,,1608386149791"));

        assertThat(floor.rating()).isEqualTo(399);
        assertThat(daily.dailyDate()).isEqualTo(LocalDate.of(2020, 12, 19));
    }

    @Test
    void rejectsInvalidRecordsWithStableReasonCodes() {
        String valid = "A0001,5rk1/5pb1/B1p4p/2p5/8/5RqP/PQ4P1/R5K1 b - - 0 26,g3f3 b2g7 g8g7 g2f3,"
                + "1500,70,95,500,fork fork,https://lichess.org/game0001,,";

        assertReason(valid, "invalid_themes");
        assertReason(valid.replace("fork fork", "fork").replace(",,", ",,1"), "invalid_daily_date");
        assertReason(valid.replace("fork fork", "fork").replace(",,", ",,99999999999999"),
                "invalid_daily_date");
        assertReason(valid.replace("fork fork", "fork").replace("1500", "99"), "invalid_rating");
        assertReason(valid.replace("fork fork", "fork").replace(",,", ",Bad'Tag,"),
                "invalid_opening_tags");
        assertReason(valid.replace("fork fork", "fork").replace("game0001", "game0001,extra"),
                "malformed_record");
        assertReason(valid.replace("fork fork", "fork").replace("https://lichess.org", "https://example.com"),
                "invalid_identity");
        assertThatThrownBy(() -> LichessPuzzleRecords.validate(LichessPuzzleRecords.parse(
                valid.replace("fork fork", "fork").replace("g3f3 b2g7", "a1a8 b2g7"))))
                .isInstanceOfSatisfying(LichessPuzzleRecords.RecordRejected.class,
                        rejected -> assertThat(rejected.reason()).isEqualTo("illegal_move_line"));
    }

    private static void assertReason(String line, String reason) {
        assertThatThrownBy(() -> LichessPuzzleRecords.parse(line))
                .isInstanceOfSatisfying(LichessPuzzleRecords.RecordRejected.class,
                        rejected -> assertThat(rejected.reason()).isEqualTo(reason));
    }
}
