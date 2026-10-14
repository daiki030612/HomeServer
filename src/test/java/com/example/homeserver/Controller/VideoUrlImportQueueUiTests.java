package com.example.homeserver.Controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class VideoUrlImportQueueUiTests {
	private static final Path SCRIPT = Path.of("src/main/resources/static/js/video/upload.js");

	@Test
	void queueUiAllowsAdditionalUrlsAndRefreshesBothJobGroups() throws IOException {
		String script = Files.readString(SCRIPT);

		assertThat(script).contains(
				"URL保存タスクを追加しました",
				"待機中 #",
				"urlJobActiveList",
				"urlJobHistoryList",
				"window.setInterval(refreshJobHistory, 3000)",
				"urlImportButton.textContent = \"URLから保存\"");
		assertThat(script).doesNotContain(
				"urlImportButton.textContent = \"保存処理中...\"",
				"window.location.href = selectedFolder");
	}

	@Test
	void queuedAndRunningCancellationKeepsPostAndCsrfHeader() throws IOException {
		String script = Files.readString(SCRIPT);

		assertThat(script).contains(
				"method: \"POST\"",
				"[csrfHeader]: csrfToken",
				"encodeURIComponent(button.dataset.jobId)");
	}
}
