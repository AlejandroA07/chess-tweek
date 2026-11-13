package com.chesstweek.gamereview;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import com.chesstweek.ChessTweekApplication;
import com.chesstweek.gamereview.RatingContext.BotGroup;
import com.chesstweek.gamereview.RatingContext.RatingPoint;
import com.chesstweek.gamereview.RatingContext.SpeedHistory;
import com.chesstweek.gamereview.RatingContext.Tally;
import com.chesstweek.gamereview.internal.persistence.GameCatalogStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GameRatingContextPersistenceTest {

    private static final Instant START = Instant.parse("2026-11-01T00:00:00Z");

    @Test
    void returnsEmptyHistoriesAndBotRecordForAnEmptyCatalog(@TempDir Path temporaryDirectory) {
        try (var application = start(temporaryDirectory)) {
            RatingContext context = application.getBean(GameRatingContext.class)
                    .ratingContext("ownerid");

            assertThat(context.histories())
                    .extracting(SpeedHistory::speed)
                    .containsExactly(
                            CatalogGame.Speed.BLITZ,
                            CatalogGame.Speed.RAPID,
                            CatalogGame.Speed.CLASSICAL);
            assertThat(context.histories()).allSatisfy(history -> {
                assertThat(history.gamesCounted()).isZero();
                assertThat(history.latestChange()).isNull();
                assertThat(history.points()).isEmpty();
            });
            assertThat(context.botRecord().total()).isEqualTo(Tally.EMPTY);
            assertThat(context.botRecord().lowestRating()).isNull();
            assertThat(context.botRecord().highestRating()).isNull();
            assertThat(context.botRecord().groups()).isEmpty();
        }
    }

    @Test
    void buildsRatingHistoryFromRatedStandardGamesOnly(@TempDir Path temporaryDirectory) {
        try (var application = start(temporaryDirectory)) {
            var store = application.getBean(GameCatalogStore.class);
            store.save("ownerid", List.of(
                    rated("blitz001", 0, CatalogGame.Speed.BLITZ, 1500, 8),
                    rated("blitz002", 1, CatalogGame.Speed.BLITZ, 1508, -6),
                    rated("blitz003", 2, CatalogGame.Speed.BLITZ, 1502, null),
                    casual("blitz004", 3, CatalogGame.Speed.BLITZ, 1490),
                    variant("blitz005", 4, CatalogGame.Speed.BLITZ, 1700, 12),
                    rated("rapid001", 5, CatalogGame.Speed.RAPID, 1600, 0),
                    rated("bullet01", 6, CatalogGame.Speed.BULLET, 1400, 5)),
                    START);
            store.save("otheracct", List.of(
                    rated("foreign1", 7, CatalogGame.Speed.BLITZ, 2200, 9)), START);

            RatingContext context = application.getBean(GameRatingContext.class)
                    .ratingContext("ownerid");

            SpeedHistory blitz = context.histories().get(0);
            assertThat(blitz.gamesCounted()).isEqualTo(3);
            assertThat(blitz.latestChange()).isNull();
            assertThat(blitz.points()).containsExactly(
                    new RatingPoint(START, 1508),
                    new RatingPoint(START.plusSeconds(3_600), 1502),
                    new RatingPoint(START.plusSeconds(7_200), 1502));

            SpeedHistory rapid = context.histories().get(1);
            assertThat(rapid.gamesCounted()).isEqualTo(1);
            assertThat(rapid.latestChange()).isZero();
            assertThat(rapid.points()).containsExactly(
                    new RatingPoint(START.plusSeconds(5 * 3_600), 1600));

            SpeedHistory classical = context.histories().get(2);
            assertThat(classical.gamesCounted()).isZero();
            assertThat(classical.points()).isEmpty();
        }
    }

    @Test
    void keepsOnlyTheMostRecentHistoryPointsButCountsEveryGame(
            @TempDir Path temporaryDirectory) {
        try (var application = start(temporaryDirectory)) {
            var games = new ArrayList<CatalogGame>();
            for (int index = 0; index < RatingContext.HISTORY_POINTS + 5; index++) {
                games.add(rated(
                        "rapid%03d".formatted(index), index, CatalogGame.Speed.RAPID,
                        1500 + index, 1));
            }
            application.getBean(GameCatalogStore.class).save("ownerid", games, START);

            SpeedHistory rapid = application.getBean(GameRatingContext.class)
                    .ratingContext("ownerid")
                    .histories()
                    .get(1);

            assertThat(rapid.gamesCounted()).isEqualTo(RatingContext.HISTORY_POINTS + 5);
            assertThat(rapid.points()).hasSize(RatingContext.HISTORY_POINTS);
            assertThat(rapid.points().getFirst().rating()).isEqualTo(1506);
            assertThat(rapid.points().getLast().rating())
                    .isEqualTo(1500 + RatingContext.HISTORY_POINTS + 5);
            assertThat(rapid.latestChange()).isEqualTo(1);
        }
    }

    @Test
    void groupsResultsAgainstLichessAiAndBotAccounts(@TempDir Path temporaryDirectory) {
        try (var application = start(temporaryDirectory)) {
            application.getBean(GameCatalogStore.class).save("ownerid", List.of(
                    againstBot("bot00001", 0, CatalogGame.Result.WIN, 1450, true),
                    againstBot("bot00002", 1, CatalogGame.Result.LOSS, 1599, false),
                    againstBot("bot00003", 2, CatalogGame.Result.DRAW, 1600, true),
                    againstBot("bot00004", 3, CatalogGame.Result.WIN, null, false),
                    againstAi("ai000001", 4, CatalogGame.Result.WIN, 3),
                    againstAi("ai000002", 5, CatalogGame.Result.LOSS, 3),
                    againstAi("ai000003", 6, CatalogGame.Result.LOSS, 6),
                    againstHuman("human001", 7, CatalogGame.Result.WIN)),
                    START);
            application.getBean(GameCatalogStore.class).save("otheracct", List.of(
                    againstBot("foreign1", 8, CatalogGame.Result.WIN, 2400, true)), START);

            RatingContext.BotRecord record = application.getBean(GameRatingContext.class)
                    .ratingContext("ownerid")
                    .botRecord();

            assertThat(record.total()).isEqualTo(new Tally(3, 1, 3));
            assertThat(record.total().games()).isEqualTo(7);
            assertThat(record.lowestRating()).isEqualTo(1450);
            assertThat(record.highestRating()).isEqualTo(1600);
            assertThat(record.groups()).containsExactly(
                    new BotGroup(BotGroup.Kind.LICHESS_AI, null, null, 3, new Tally(1, 0, 1)),
                    new BotGroup(BotGroup.Kind.LICHESS_AI, null, null, 6, new Tally(0, 0, 1)),
                    new BotGroup(
                            BotGroup.Kind.BOT_RATING_BAND, 1400, 1599, null, new Tally(1, 0, 1)),
                    new BotGroup(
                            BotGroup.Kind.BOT_RATING_BAND, 1600, 1799, null, new Tally(0, 1, 0)),
                    new BotGroup(BotGroup.Kind.BOT_UNRATED, null, null, null, new Tally(1, 0, 0)));
        }
    }

    @Test
    void rejectsAnInvalidAccountId(@TempDir Path temporaryDirectory) {
        try (var application = start(temporaryDirectory)) {
            var context = application.getBean(GameRatingContext.class);

            assertThatThrownBy(() -> context.ratingContext("bad account"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> context.ratingContext(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static CatalogGame rated(
            String id, int hour, CatalogGame.Speed speed, int rating, Integer change) {
        return game(id, hour, speed, true, CatalogGame.Variant.STANDARD, rating, change,
                CatalogGame.Result.WIN, human());
    }

    private static CatalogGame casual(String id, int hour, CatalogGame.Speed speed, int rating) {
        return game(id, hour, speed, false, CatalogGame.Variant.STANDARD, rating, null,
                CatalogGame.Result.WIN, human());
    }

    private static CatalogGame variant(
            String id, int hour, CatalogGame.Speed speed, int rating, Integer change) {
        return game(id, hour, speed, true, CatalogGame.Variant.CRAZYHOUSE, rating, change,
                CatalogGame.Result.WIN, human());
    }

    private static CatalogGame againstBot(
            String id, int hour, CatalogGame.Result result, Integer botRating, boolean rated) {
        var bot = new CatalogGame.Opponent(
                CatalogGame.OpponentKind.USER, "studybot", "StudyBot", botRating, "BOT", null);
        return game(id, hour, CatalogGame.Speed.BLITZ, rated, CatalogGame.Variant.STANDARD,
                1500, null, result, bot);
    }

    private static CatalogGame againstAi(
            String id, int hour, CatalogGame.Result result, int level) {
        var ai = new CatalogGame.Opponent(
                CatalogGame.OpponentKind.AI, null, "Lichess AI", null, null, level);
        return game(id, hour, CatalogGame.Speed.BLITZ, false, CatalogGame.Variant.STANDARD,
                1500, null, result, ai);
    }

    private static CatalogGame againstHuman(String id, int hour, CatalogGame.Result result) {
        return game(id, hour, CatalogGame.Speed.BLITZ, false, CatalogGame.Variant.STANDARD,
                1500, null, result, human());
    }

    private static CatalogGame.Opponent human() {
        return new CatalogGame.Opponent(
                CatalogGame.OpponentKind.USER, "opponent", "Opponent", 1550, null, null);
    }

    private static CatalogGame game(
            String id,
            int hour,
            CatalogGame.Speed speed,
            boolean rated,
            CatalogGame.Variant variant,
            int rating,
            Integer change,
            CatalogGame.Result result,
            CatalogGame.Opponent opponent) {
        Instant playedAt = START.plusSeconds(hour * 3_600L);
        return new CatalogGame(
                id,
                playedAt,
                playedAt.plusSeconds(300),
                CatalogGame.Status.RESIGN,
                result,
                CatalogGame.Color.WHITE,
                rating,
                change,
                opponent,
                speed,
                rated,
                variant,
                new CatalogGame.TimeControl(300, 3, null),
                null,
                null,
                "e4 e5",
                null,
                List.of(),
                variant == CatalogGame.Variant.STANDARD);
    }

    private ConfigurableApplicationContext start(Path temporaryDirectory) {
        return new SpringApplicationBuilder(ChessTweekApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--chess-tweek.database.path="
                                + temporaryDirectory.resolve("runtime/chess-tweek.db"),
                        "--logging.level.root=OFF");
    }
}
