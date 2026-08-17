package com.example.homeserver.Config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(StorageMonitoringProperties.class)
public class StorageMonitoringConfig {
}
