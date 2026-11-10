package com.chesstweek.identity;

import java.net.URI;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

final class HttpLichessAccountClient implements LichessAccountClient {

    private static final URI ACCOUNT_ENDPOINT = URI.create("https://lichess.org/api/account");

    private final RestClient restClient;

    HttpLichessAccountClient() {
        this(LichessHttpClientFactory.create());
    }

    HttpLichessAccountClient(RestClient restClient) {
        this.restClient = restClient;
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
            throw new LichessAccountException(
                    exception.getStatusCode().value() == 401, exception);
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
