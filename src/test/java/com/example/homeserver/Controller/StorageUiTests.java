package com.example.homeserver.Controller;
import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
class StorageUiTests {
    @Test void headerContainsResponsiveStorageIndicatorWithoutServerPath() throws Exception {
        String header = Files.readString(Path.of("src/main/resources/templates/fragments/header.html"));
        String css = Files.readString(Path.of("src/main/resources/static/css/style.css"));
        assertThat(header).contains("storage-status", "storageInfo.freeDisplay", "storageInfo.totalDisplay").doesNotContain("video.storage.path");
        assertThat(css).contains("@media (max-width:760px)", ".storage-status");
    }
}
