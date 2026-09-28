package com.evision.collection.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "evision.collection")
public record CollectionProperties(
        String statusCron,
        String catalogCron,
        @DefaultValue("true") boolean enabled,
        @DefaultValue("1000") int persistBatchSize) {
}
