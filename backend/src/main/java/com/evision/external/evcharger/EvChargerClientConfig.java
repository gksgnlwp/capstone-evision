package com.evision.external.evcharger;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EvChargerClientConfig {

    @Bean
    public Sleeper sleeper() {
        return duration -> Thread.sleep(duration.toMillis());
    }
}
