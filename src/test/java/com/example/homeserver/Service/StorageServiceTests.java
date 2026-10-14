package com.example.homeserver.Service;

import static org.assertj.core.api.Assertions.assertThat;
import com.example.homeserver.Config.StorageMonitoringProperties;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class StorageServiceTests {
    private static final long GB = 1024L * 1024 * 1024;
    private final StorageMonitoringProperties thresholds = new StorageMonitoringProperties(90, 10 * GB);

    @Test void aggregatesDifferentStoresAndExcludesDuplicateFileStore() {
        StorageService service = new StorageService(List.of(Path.of("video"), Path.of("thumb"), Path.of("temp")), thresholds,
                path -> path.endsWith("temp") ? new StorageService.StoreSnapshot("B", 200 * GB, 100 * GB)
                        : new StorageService.StoreSnapshot("A", 100 * GB, 40 * GB));
        StorageInfo info = service.current();
        assertThat(info.available()).isTrue();
        assertThat(info.totalBytes()).isEqualTo(300 * GB);
        assertThat(info.freeBytes()).isEqualTo(140 * GB);
        assertThat(info.usedBytes()).isEqualTo(160 * GB);
        assertThat(info.usagePercent()).isEqualTo(53.3);
    }

    @Test void failureReturnsUnavailableWithoutThrowing() {
        StorageInfo info = new StorageService(List.of(Path.of("missing")), thresholds, path -> { throw new IOException("failed"); }).current();
        assertThat(info.available()).isFalse();
    }

    @Test void warningUsesUsageOrFreeSpaceThreshold() {
        StorageInfo byUsage = service(100 * GB, 9 * GB).current();
        StorageInfo byFree = service(100 * GB, 10 * GB).current();
        StorageInfo healthy = service(100 * GB, 20 * GB).current();
        assertThat(byUsage.warning()).isTrue();
        assertThat(byFree.warning()).isTrue();
        assertThat(healthy.warning()).isFalse();
    }

    @Test void nonexistentConfiguredDirectoryUsesExistingParentFileStore() {
        StorageInfo info = new StorageService(List.of(Path.of("target", "does-not-exist", "nested")), thresholds).current();
        assertThat(info.available()).isTrue();
        assertThat(info.totalBytes()).isPositive();
    }

    private StorageService service(long total, long free) {
        return new StorageService(List.of(Path.of("store")), thresholds, path -> new StorageService.StoreSnapshot("A", total, free));
    }
}
