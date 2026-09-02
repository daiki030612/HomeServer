package com.example.homeserver.Controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class VideoThumbnailUiTests {
	private static final Path LIST = Path.of("src/main/resources/templates/video/list.html");
	private static final Path SCRIPT = Path.of("src/main/resources/static/js/video/video-list.js");

	@Test
	void cardOffersChangeResetAndNativeIphoneCompatiblePicker() throws Exception {
		String html = Files.readString(LIST);
		assertThat(html).contains(
				"サムネイルを変更", "サムネイルを元に戻す",
				"type=\"file\" name=\"thumbnail\"",
				"accept=\"image/jpeg,image/png,image/webp\"",
				"@{/videos/{id}/thumbnail(id=${video.id}",
				"th:hidden=\"${video.customThumbnailName == null}\"");
	}

	@Test
	void uploadUsesMultipartCsrfAndUpdatesOnlyCardImageWithoutBase64OrCanvas() throws Exception {
		String script = Files.readString(SCRIPT);
		String code = script.substring(script.indexOf("カスタムサムネイル"), script.indexOf("動画メニュー"));
		assertThat(code).contains(
				"new FormData()", "formData.append(\"thumbnail\", file, file.name)",
				"credentials: \"same-origin\"", "[csrfHeader]: csrfToken",
				"image.src = uploadUrl + \"?v=\"", "input.click()",
				"reset.hidden = result.custom !== true")
				.doesNotContain("base64", "FileReader", "canvas", "window.location.reload", "alert(");
	}
}
