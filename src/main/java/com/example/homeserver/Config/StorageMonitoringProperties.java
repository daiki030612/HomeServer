package com.example.homeserver.Config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("storage.monitoring")
public record StorageMonitoringProperties(double warningUsagePercent, long warningFreeBytes) {
    public StorageMonitoringProperties {
        if (warningUsagePercent < 0 || warningUsagePercent > 100) {
            throw new IllegalArgumentException("storage.monitoring.warning-usage-percent must be between 0 and 100");
        }
        if (warningFreeBytes < 0) {
            throw new IllegalArgumentException("storage.monitoring.warning-free-bytes must not be negative");
        }
    }
}
