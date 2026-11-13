package com.chesstweek.composition.web;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.chesstweek.gamereview.CatalogGame;
import com.chesstweek.gamereview.GameRatingContext;
import com.chesstweek.gamereview.RatingContext;
import com.chesstweek.gamereview.RatingContext.BotGroup;
import com.chesstweek.gamereview.RatingContext.Tally;
import com.chesstweek.identity.LichessSessionAuthorization;
import com.chesstweek.identity.LichessSessionAuthorizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DashboardRatingContextControllerTest {

    private static final jakarta.servlet.http.Cookie SESSION =
            new jakarta.servlet.http.Cookie("__Host-chess_tweek_session", "sealed-session");

    @Test
    void returnsTheAuthorizedAccountsRatingHistoryAndBotRecord() throws Exception {
        var accountSeen = new AtomicReference<String>();
        GameRatingContext ratingContext = accountId -> {
            accountSeen.set(accountId);
            return context();
        };

        mockMvc(connected(), ratingContext)
                .perform(get("/api/dashboard/rating-context").cookie(SESSION))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.ratings[0].speed").value("rapid"))
                .andExpect(jsonPath("$.ratings[0].latestChange").doesNotExist())
                .andExpect(jsonPath("$.ratings[1].speed").value("blitz"))
                .andExpect(jsonPath("$.ratings[1].gamesCounted").value(2))
                .andExpect(jsonPath("$.ratings[1].latestChange").value(-6))
                .andExpect(jsonPath("$.ratings[1].points[0].playedAt")
                        .value("2026-11-01T00:00:00Z"))
                .andExpect(jsonPath("$.ratings[1].points[0].rating").value(1508))
                .andExpect(jsonPath("$.ratings[1].points[1].rating").value(1502))
                .andExpect(jsonPath("$.ratings[2].speed").value("bullet"))
                .andExpect(jsonPath("$.ratings[3].speed").value("classical"))
                .andExpect(jsonPath("$.botRecord.wins").value(2))
                .andExpect(jsonPath("$.botRecord.draws").value(1))
                .andExpect(jsonPath("$.botRecord.losses").value(1))
                .andExpect(jsonPath("$.botRecord.games").value(4))
                .andExpect(jsonPath("$.botRecord.lowestRating").value(1450))
                .andExpect(jsonPath("$.botRecord.highestRating").value(1600))
                .andExpect(jsonPath("$.botRecord.groups[0].type").value("lichess_ai"))
                .andExpect(jsonPath("$.botRecord.groups[0].aiLevel").value(3))
                .andExpect(jsonPath("$.botRecord.groups[0].games").value(1))
                .andExpect(jsonPath("$.botRecord.groups[1].type").value("bot_rating_band"))
                .andExpect(jsonPath("$.botRecord.groups[1].ratingFrom").value(1400))
                .andExpect(jsonPath("$.botRecord.groups[1].ratingTo").value(1599))
                .andExpect(jsonPath("$.botRecord.groups[1].wins").value(1))
                .andExpect(jsonPath("$.botRecord.groups[2].type").value("bot_unrated"))
                .andExpect(content().string(not(containsString("private_token"))));

        assertThat(accountSeen).hasValue("owner-id");
    }

    @Test
    void deniesDisconnectedRequestsBeforeReadingAccountData() throws Exception {
        var reads = new AtomicInteger();
        GameRatingContext ratingContext = accountId -> {
            reads.incrementAndGet();
            return context();
        };

        mockMvc(sealedSession -> LichessSessionAuthorization.disconnected(true), ratingContext)
                .perform(get("/api/dashboard/rating-context").cookie(SESSION))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")))
                .andExpect(jsonPath("$.error").value("not_connected"))
                .andExpect(content().string(not(containsString("1508"))));

        assertThat(reads).hasValue(0);
    }

    @Test
    void reportsAnUnavailableSessionWithoutReadingAccountData() throws Exception {
        var reads = new AtomicInteger();
        GameRatingContext ratingContext = accountId -> {
            reads.incrementAndGet();
            return context();
        };

        mockMvc(sealedSession -> LichessSessionAuthorization.unavailable(), ratingContext)
                .perform(get("/api/dashboard/rating-context").cookie(SESSION))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("session_unavailable"));
        mockMvc(sealedSession -> LichessSessionAuthorization.rateLimited(60L), ratingContext)
                .perform(get("/api/dashboard/rating-context").cookie(SESSION))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("session_unavailable"));

        assertThat(reads).hasValue(0);
    }

    @Test
    void returnsASafeRecoverableErrorWhenStorageFails() throws Exception {
        GameRatingContext ratingContext = accountId -> {
            throw new DataAccessResourceFailureException("private database detail");
        };

        mockMvc(connected(), ratingContext)
                .perform(get("/api/dashboard/rating-context").cookie(SESSION))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.error").value("rating_context_unavailable"))
                .andExpect(content().string(not(containsString("private database detail"))));
    }

    private static MockMvc mockMvc(
            LichessSessionAuthorizer authorizer, GameRatingContext ratingContext) {
        return MockMvcBuilders
                .standaloneSetup(new DashboardRatingContextController(authorizer, ratingContext))
                .build();
    }

    private static LichessSessionAuthorizer connected() {
        return sealedSession -> LichessSessionAuthorization.connected(
                "owner-id", "ChessOwner", "liu_private_token_123");
    }

    private static RatingContext context() {
        Instant start = Instant.parse("2026-11-01T00:00:00Z");
        return new RatingContext(
                List.of(
                        new RatingContext.SpeedHistory(
                                CatalogGame.Speed.RAPID, 0, null, List.of()),
                        new RatingContext.SpeedHistory(
                                CatalogGame.Speed.BLITZ,
                                2,
                                -6,
                                List.of(
                                        new RatingContext.RatingPoint(start, 1508),
                                        new RatingContext.RatingPoint(
                                                start.plusSeconds(3_600), 1502))),
                        new RatingContext.SpeedHistory(
                                CatalogGame.Speed.BULLET, 0, null, List.of()),
                        new RatingContext.SpeedHistory(
                                CatalogGame.Speed.CLASSICAL, 0, null, List.of())),
                new RatingContext.BotRecord(
                        new Tally(2, 1, 1),
                        1450,
                        1600,
                        List.of(
                                new BotGroup(
                                        BotGroup.Kind.LICHESS_AI, null, null, 3,
                                        new Tally(0, 0, 1)),
                                new BotGroup(
                                        BotGroup.Kind.BOT_RATING_BAND, 1400, 1599, null,
                                        new Tally(1, 1, 0)),
                                new BotGroup(
                                        BotGroup.Kind.BOT_UNRATED, null, null, null,
                                        new Tally(1, 0, 0)))));
    }
}
