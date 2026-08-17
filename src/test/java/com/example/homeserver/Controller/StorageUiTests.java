package com.example.homeserver.Controller;
import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
class StorageUiTests {
    @Test void headerContainsResponsiveStorageIndicatorWithoutServerPath() throws Exception {
        String header = Files.readString(Path.of("src/main/resources/templates/fragments/header.html"));
        String css = Files.readString(Path.of("src/main/resources/static/css/style.css"));
        String unified = Files.readString(Path.of("src/main/resources/static/css/unified-header.css"));
        String menu = Files.readString(Path.of("src/main/resources/static/js/header-menu.js"));
        assertThat(header).contains("unified-header", "data-header-menu-button", "app-switch-link active", "storage-mobile", "storageInfo.totalDisplay").doesNotContain("video.storage.path");
        assertThat(css).contains(".storage-status");
        assertThat(unified).contains("env(safe-area-inset-top)", "grid-template-columns:44px", "@media(max-width:700px)", "overflow:hidden");
        assertThat(menu).contains("addEventListener('click'", "aria-expanded", "Escape");
    }
}
