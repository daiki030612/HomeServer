package com.example.homeserver.Controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class UnifiedVideoUiTests {
    private static final Path CSS = Path.of("src/main/resources/static/css");
    private static final Path TEMPLATES = Path.of("src/main/resources/templates");

    @Test
    void pagesUseTheSharedHomeServerDesignSystem() throws IOException {
        String design = Files.readString(CSS.resolve("design-system.css"));
        String list = Files.readString(TEMPLATES.resolve("video/list.html"));
        String upload = Files.readString(TEMPLATES.resolve("video/upload.html"));
        String play = Files.readString(TEMPLATES.resolve("video/play.html"));

        assertThat(design).contains(
                "--bg: #0d0f14",
                "--surface: #171a22",
                "--line: #282c38",
                "--accent: #8b5cf6");
        assertThat(list).contains("@{/css/design-system.css}", "@{/css/video-library.css}");
        assertThat(upload).contains("@{/css/design-system.css}", "@{/css/upload.css}");
        assertThat(play).contains("@{/css/design-system.css}", "@{/css/play.css}");
    }

    @Test
    void libraryCardsAndEmptyStateRemainResponsive() throws IOException {
        String html = Files.readString(TEMPLATES.resolve("video/list.html"));
        String css = Files.readString(CSS.resolve("video-library.css"));

        assertThat(html).contains(
                "class=\"section-heading\"",
                "class=\"media-kind\">動画",
                "class=\"library-empty\"");
        assertThat(css).contains(
                ".folder-card,",
                ".video-card",
                "aspect-ratio: 16 / 9",
                "repeat(2, minmax(0, 1fr))",
                "@media (max-width: 350px)");
    }

    @Test
    void playbackControlsHaveAccessibleNamesAndKeepFullscreenRules() throws IOException {
        String html = Files.readString(TEMPLATES.resolve("video/play.html"));
        String css = Files.readString(CSS.resolve("play.css"));

        assertThat(html).contains(
                "aria-label=\"再生\"",
                "aria-label=\"再生位置\"",
                "aria-label=\"音量\"",
                "aria-label=\"全画面表示\"");
        assertThat(css).contains(
                ".video-player-container:fullscreen",
                "object-fit:contain",
                "@media(orientation:landscape)",
                "env(safe-area-inset-bottom)");
    }

    @Test
    void uploadAndUrlJobsUseSeparateResponsivePanels() throws IOException {
        String html = Files.readString(TEMPLATES.resolve("video/upload.html"));
        String css = Files.readString(CSS.resolve("upload.css"));

        assertThat(html).contains(
                "class=\"upload-card\"",
                "class=\"upload-card url-import-card\"",
                "class=\"upload-card url-job-history\"");
        assertThat(css).contains(
                ".url-job-state[data-state=\"COMPLETED\"]",
                ".url-job-cancel",
                "max-height:520px",
                "@media(max-width:600px)");
    }
}
