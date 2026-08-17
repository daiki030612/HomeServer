package com.example.homeserver.Service;

import com.example.homeserver.Config.StorageMonitoringProperties;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class StorageService {
    private static final Logger log = LoggerFactory.getLogger(StorageService.class);
    private final List<Path> storagePaths;
    private final StorageMonitoringProperties properties;
    private final StorageProbe probe;

    @Autowired
    public StorageService(@Value("${video.storage.path}") String videoPath,
            @Value("${thumbnail.storage.path}") String thumbnailPath,
            @Value("${spring.servlet.multipart.location}") String temporaryPath,
            StorageMonitoringProperties properties) {
        this(List.of(Path.of(videoPath), Path.of(thumbnailPath), Path.of(temporaryPath)), properties,
                StorageService::probeFileStore);
    }

    StorageService(List<Path> storagePaths, StorageMonitoringProperties properties, StorageProbe probe) {
        this.storagePaths = List.copyOf(storagePaths);
        this.properties = properties;
        this.probe = probe;
    }

    StorageService(List<Path> storagePaths, StorageMonitoringProperties properties) {
        this(storagePaths, properties, StorageService::probeFileStore);
    }

    public StorageInfo current() {
        try {
            Map<String, StoreSnapshot> stores = new LinkedHashMap<>();
            for (Path configured : storagePaths) {
                StoreSnapshot snapshot = probe.read(configured.toAbsolutePath().normalize());
                stores.putIfAbsent(snapshot.key(), snapshot);
            }
            if (stores.isEmpty()) return StorageInfo.unavailable();
            long total = 0;
            long free = 0;
            boolean warning = false;
            for (StoreSnapshot store : stores.values()) {
                total = Math.addExact(total, store.totalBytes());
                free = Math.addExact(free, store.freeBytes());
                double percent = usage(store.totalBytes(), store.freeBytes());
                warning |= percent >= properties.warningUsagePercent()
                        || store.freeBytes() <= properties.warningFreeBytes();
            }
            long used = Math.max(0, total - free);
            return new StorageInfo(total, used, free, usage(total, free), warning, total > 0);
        } catch (Exception e) {
            log.warn("Storage capacity could not be obtained: {}", e.getClass().getSimpleName());
            return StorageInfo.unavailable();
        }
    }

    private static StoreSnapshot probeFileStore(Path path) throws IOException {
        Path existing = path;
        while (existing != null && !Files.exists(existing)) existing = existing.getParent();
        if (existing == null) throw new IOException("No existing parent for configured storage");
        FileStore store = Files.getFileStore(existing);
        long total = store.getTotalSpace();
        long free = Math.min(total, store.getUsableSpace());
        return new StoreSnapshot(store.toString() + '|' + store.name() + '|' + store.type(), total, free);
    }

    private static double usage(long total, long free) {
        return total <= 0 ? 0 : Math.round((total - Math.min(total, free)) * 1000.0 / total) / 10.0;
    }

    @FunctionalInterface interface StorageProbe { StoreSnapshot read(Path path) throws IOException; }
    record StoreSnapshot(String key, long totalBytes, long freeBytes) { }
}
