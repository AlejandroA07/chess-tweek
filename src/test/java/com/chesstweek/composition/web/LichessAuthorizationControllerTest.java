package com.chesstweek.composition.web;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.util.UriComponentsBuilder;

import com.chesstweek.identity.LichessAuthorizationService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LichessAuthorizationControllerTest {

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new LichessAuthorizationController(new LichessAuthorizationService()))
            .build();

    @Test
    void redirectsToLichessWithFreshPkceAndOnlyTheApprovedScope() throws Exception {
        var first = authorizationRedirect();
        var second = authorizationRedirect();

        assertThat(first.getScheme()).isEqualTo("https");
        assertThat(first.getHost()).isEqualTo("lichess.org");
        assertThat(first.getPath()).isEqualTo("/oauth");

        var firstQuery = UriComponentsBuilder.fromUri(first).build().getQueryParams();
        var secondQuery = UriComponentsBuilder.fromUri(second).build().getQueryParams();

        assertThat(firstQuery).containsEntry("response_type", java.util.List.of("code"));
        assertThat(firstQuery).containsEntry("client_id", java.util.List.of("chess-tweek-macos-local"));
        assertThat(firstQuery).containsEntry(
                "redirect_uri",
                java.util.List.of("http://127.0.0.1:8080/api/lichess/callback"));
        assertThat(firstQuery).containsEntry("code_challenge_method", java.util.List.of("S256"));
        assertThat(firstQuery).containsEntry("scope", java.util.List.of("puzzle:read"));
        assertThat(firstQuery.getFirst("state")).matches("[A-Za-z0-9_-]{43}");
        assertThat(firstQuery.getFirst("code_challenge")).matches("[A-Za-z0-9_-]{43}");
        assertThat(firstQuery).doesNotContainKey("code_verifier");
        assertThat(firstQuery.keySet()).containsExactlyInAnyOrder(
                "response_type",
                "client_id",
                "redirect_uri",
                "code_challenge",
                "code_challenge_method",
                "scope",
                "state");
        assertThat(secondQuery.getFirst("state")).isNotEqualTo(firstQuery.getFirst("state"));
        assertThat(secondQuery.getFirst("code_challenge")).isNotEqualTo(firstQuery.getFirst("code_challenge"));
    }

    @Test
    void acceptsTheExactLocalOrigin() throws Exception {
        mockMvc.perform(post("/api/lichess/authorize")
                        .header("Origin", "http://127.0.0.1:8080")
                        .header("Sec-Fetch-Site", "same-origin"))
                .andExpect(status().isFound());
    }

    @Test
    void rejectsAnUntrustedOrigin() throws Exception {
        mockMvc.perform(post("/api/lichess/authorize")
                        .header("Origin", "https://attacker.example"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Location"));
    }

    @Test
    void rejectsCrossSiteBrowserInitiation() throws Exception {
        mockMvc.perform(post("/api/lichess/authorize")
                        .header("Origin", "http://127.0.0.1:8080")
                        .header("Sec-Fetch-Site", "cross-site"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Location"));
    }

    @Test
    void rejectsInitiationWithoutAnOrigin() throws Exception {
        mockMvc.perform(post("/api/lichess/authorize"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Location"));
    }

    private URI authorizationRedirect() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/lichess/authorize")
                        .header("Origin", "http://127.0.0.1:8080")
                        .header("Sec-Fetch-Site", "same-origin"))
                .andExpect(status().isFound())
                .andReturn();
        return URI.create(result.getResponse().getHeader("Location"));
    }
}
