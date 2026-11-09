package com.chesstweek.composition.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HealthControllerTest {

    @Test
    void reportsHealthy() {
        var response = new HealthController().health();

        assertThat(response.status()).isEqualTo("ok");
    }
}
