package com.chesstweek.gamereview;

public interface GameRatingContext {

    /** Rating history and bot record built from the account's stored games from one site. */
    RatingContext ratingContext(String accountId, GameRef.Source source);

    /** The Lichess rating context. */
    default RatingContext ratingContext(String accountId) {
        return ratingContext(accountId, GameRef.Source.LICHESS);
    }
}
