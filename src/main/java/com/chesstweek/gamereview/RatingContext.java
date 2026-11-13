package com.chesstweek.gamereview;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Rating and bot-opponent context derived only from games stored in the local catalog.
 */
public record RatingContext(List<SpeedHistory> histories, BotRecord botRecord) {

    /** Speeds whose rating history the Dashboard shows, in display order. */
    public static final List<CatalogGame.Speed> HISTORY_SPEEDS = List.of(
            CatalogGame.Speed.BLITZ, CatalogGame.Speed.RAPID, CatalogGame.Speed.CLASSICAL);

    /** Most recent rated games kept per speed for the history line. */
    public static final int HISTORY_POINTS = 30;

    /** Width of each BOT opponent rating group. */
    public static final int BOT_RATING_BAND = 200;

    public RatingContext {
        histories = List.copyOf(histories);
        Objects.requireNonNull(botRecord);
    }

    /**
     * Rated standard games at one speed. {@code points} are oldest first; {@code latestChange}
     * is the Lichess rating change of the most recent game, or null when Lichess supplied none.
     */
    public record SpeedHistory(
            CatalogGame.Speed speed,
            int gamesCounted,
            Integer latestChange,
            List<RatingPoint> points) {

        public SpeedHistory {
            Objects.requireNonNull(speed);
            points = List.copyOf(points);
            if (gamesCounted < points.size()) {
                throw new IllegalArgumentException("Games counted must include every point");
            }
        }
    }

    /** The owner's rating after one game: the pre-game rating plus its Lichess change. */
    public record RatingPoint(Instant playedAt, int rating) {

        public RatingPoint {
            Objects.requireNonNull(playedAt);
        }
    }

    public record Tally(int wins, int draws, int losses) {

        public static final Tally EMPTY = new Tally(0, 0, 0);

        public Tally {
            if (wins < 0 || draws < 0 || losses < 0) {
                throw new IllegalArgumentException("Tallies cannot be negative");
            }
        }

        public int games() {
            return wins + draws + losses;
        }

        public Tally plus(CatalogGame.Result result, int games) {
            return switch (result) {
                case WIN -> new Tally(wins + games, draws, losses);
                case DRAW -> new Tally(wins, draws + games, losses);
                case LOSS -> new Tally(wins, draws, losses + games);
            };
        }
    }

    /**
     * Results against Lichess AI and BOT accounts, rated and casual. {@code lowestRating} and
     * {@code highestRating} cover rated BOT opponents and are null when there are none.
     */
    public record BotRecord(
            Tally total,
            Integer lowestRating,
            Integer highestRating,
            List<BotGroup> groups) {

        public BotRecord {
            Objects.requireNonNull(total);
            groups = List.copyOf(groups);
        }
    }

    /**
     * One opponent group: a BOT rating band ({@code ratingFrom} through {@code ratingTo}), BOT
     * accounts without a displayed rating, or one Lichess AI level.
     */
    public record BotGroup(
            Kind kind,
            Integer ratingFrom,
            Integer ratingTo,
            Integer aiLevel,
            Tally tally) {

        public enum Kind {
            LICHESS_AI,
            BOT_RATING_BAND,
            BOT_UNRATED
        }

        public BotGroup {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(tally);
        }
    }
}
