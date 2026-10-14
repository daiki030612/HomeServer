package com.example.homeserver.Controller;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class DownloadUiTests {
	private static final Path LIST = Path.of("src/main/resources/templates/video/list.html");

	@Test
	void videoAndFolderMenusUseDirectContextPathAwareDownloadLinks() throws Exception {
		String html = Files.readString(LIST);

		assertTrue(html.contains("class=\"video-download-link\" th:href=\"@{/videos/{id}/download(id=${video.id})}\""));
		assertTrue(html.contains("class=\"folder-download-link\" th:href=\"@{/folders/{id}/download(id=${folder.id})}\""));
		assertTrue(html.contains(">ダウンロード</a>"));
	}
}
