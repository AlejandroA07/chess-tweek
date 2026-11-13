package com.chesstweek.gamereview.internal.engine;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StockfishProperties.class)
class StockfishConfiguration {

    @Bean
    StockfishLocator stockfishLocator(StockfishProperties properties) {
        return StockfishLocator.homebrew(properties.path());
    }

    @Bean
    EngineProcessLauncher engineProcessLauncher() {
        return new NativeEngineProcessLauncher();
    }
}
