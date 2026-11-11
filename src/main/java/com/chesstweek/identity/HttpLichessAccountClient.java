package com.chesstweek.identity;

import java.net.URI;
import java.time.Clock;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.chesstweek.shared.http.RetryAfter;

final class HttpLichessAccountClient implements LichessAccountClient {

    private static final URI ACCOUNT_ENDPOINT = URI.create("https://lichess.org/api/account");

    private final RestClient restClient;
    private final Clock clock;

    HttpLichessAccountClient() {
        this(LichessHttpClientFactory.create(), Clock.systemUTC());
    }

    HttpLichessAccountClient(RestClient restClient) {
        this(restClient, Clock.systemUTC());
    }

    HttpLichessAccountClient(RestClient restClient, Clock clock) {
        this.restClient = restClient;
        this.clock = clock;
    }

    @Override
    public LichessAccount currentAccount(String accessToken) {
        try {
            AccountResponse response = restClient.get()
                    .uri(ACCOUNT_ENDPOINT)
                    .headers(headers -> headers.setBearerAuth(accessToken))
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(AccountResponse.class);
            if (response == null) {
                throw new LichessAccountException(false, null);
            }
            return response.toAccount();
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (status == 401) {
                throw new LichessAccountException(
                        LichessAccountException.Reason.UNAUTHORIZED, null, exception);
            }
            if (status == 429) {
                throw new LichessAccountException(
                        LichessAccountException.Reason.RATE_LIMITED,
                        RetryAfter.parse(
                                exception.getResponseHeaders() == null
                                        ? null
                                        : exception.getResponseHeaders().getFirst("Retry-After"),
                                clock.instant()),
                        exception);
            }
            throw new LichessAccountException(
                    LichessAccountException.Reason.UNAVAILABLE, null, exception);
        } catch (RestClientException exception) {
            throw new LichessAccountException(false, exception);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AccountResponse(String id, String username, Perfs perfs) {

        private LichessAccount toAccount() {
            LichessRatings ratings = perfs == null
                    ? new LichessRatings(null, null, null, null, null)
                    : perfs.toRatings();
            return new LichessAccount(id, username, ratings);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Perfs(
            Perf bullet,
            Perf blitz,
            Perf rapid,
            Perf classical,
            Perf correspondence) {

        private LichessRatings toRatings() {
            return new LichessRatings(
                    rating(bullet),
                    rating(blitz),
                    rating(rapid),
                    rating(classical),
                    rating(correspondence));
        }

        private static Integer rating(Perf performance) {
            return performance == null ? null : performance.rating();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Perf(Integer rating) {}
}
