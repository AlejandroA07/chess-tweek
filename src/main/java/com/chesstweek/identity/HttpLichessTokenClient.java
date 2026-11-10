package com.chesstweek.identity;

import java.net.URI;

import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

final class HttpLichessTokenClient implements LichessTokenClient {

    private static final URI TOKEN_ENDPOINT = URI.create("https://lichess.org/api/token");
    private static final String CLIENT_ID = "chess-tweek-macos-local";
    private static final String REDIRECT_URI = "http://127.0.0.1:8080/api/lichess/callback";

    private final RestClient restClient;

    HttpLichessTokenClient() {
        this(LichessHttpClientFactory.create());
    }

    HttpLichessTokenClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public LichessToken exchange(String code, String verifier) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("code_verifier", verifier);
        form.add("redirect_uri", REDIRECT_URI);
        form.add("client_id", CLIENT_ID);

        try {
            LichessToken token = restClient.post()
                    .uri(TOKEN_ENDPOINT)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .retrieve()
                    .body(LichessToken.class);
            if (token == null) {
                throw new LichessTokenExchangeException(false, null);
            }
            return token;
        } catch (RestClientResponseException exception) {
            throw new LichessTokenExchangeException(
                    exception.getStatusCode().is4xxClientError(), exception);
        } catch (RestClientException exception) {
            throw new LichessTokenExchangeException(false, exception);
        }
    }
}
