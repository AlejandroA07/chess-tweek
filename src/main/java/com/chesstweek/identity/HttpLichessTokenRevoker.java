package com.chesstweek.identity;

import java.net.URI;

import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

final class HttpLichessTokenRevoker implements LichessTokenRevoker {

    private static final URI TOKEN_ENDPOINT = URI.create("https://lichess.org/api/token");

    private final RestClient restClient;

    HttpLichessTokenRevoker() {
        this(LichessHttpClientFactory.create());
    }

    HttpLichessTokenRevoker(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public void revoke(String accessToken) {
        try {
            restClient.delete()
                    .uri(TOKEN_ENDPOINT)
                    .headers(headers -> headers.setBearerAuth(accessToken))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException exception) {
            throw new LichessRevocationException(exception);
        }
    }

}
