package com.example.homeserver.Controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class VideoFavoriteUiTests {
	private static final Path LIST = Path.of("src/main/resources/templates/video/list.html");
	private static final Path SCRIPT = Path.of("src/main/resources/static/js/video/video-list.js");
	private static final Path CSS = Path.of("src/main/resources/static/css/video-library.css");

	@Test
	void serverRendersAccessibleInitialFavoriteStateAndFilter() throws Exception {
		String html = Files.readString(LIST);

		assertThat(html).contains(
				"class=\"favorite-button\"",
				"${video.favorite} ? ' is-favorite'",
				"th:data-favorite=\"${video.favorite}\"",
				"@{/videos/{id}/favorite(id=${video.id})}",
				"th:aria-pressed=\"${video.favorite}\"",
				"'お気に入りから削除' : 'お気に入りに追加'",
				"name=\"favorite\" value=\"true\" th:checked=\"${favoriteOnly}\"",
				"onchange=\"applyFavoriteFilter(this)\"");
	}

	@Test
	void checkboxAddsOrRemovesFavoriteQueryWhileKeepingOtherFilters() throws Exception {
		String script = Files.readString(SCRIPT);

		assertThat(script).contains(
				"new URLSearchParams(new FormData(form))",
				"params.set(\"favorite\", \"true\")",
				"params.delete(\"favorite\")",
				"window.location.assign(window.location.pathname",
				"new URLSearchParams(window.location.search).get(\"favorite\") === \"true\"",
				"button.closest(\".video-card\")?.remove()");
	}

	@Test
	void optimisticUpdateSendsCsrfAndRollsBackWithoutReload() throws Exception {
		String script = Files.readString(SCRIPT);
		String favoriteCode = script.substring(0, script.indexOf("動画メニュー"));

		assertThat(favoriteCode).contains(
				"event.preventDefault()",
				"event.stopPropagation()",
				"button.disabled = true",
				"method: \"POST\"",
				"credentials: \"same-origin\"",
				"[csrfHeader]: csrfToken",
				"JSON.stringify({ favorite: favorite })",
				"setFavoriteButtonState(button, previous)",
				"aria-label")
				.doesNotContain("window.location.reload", "alert(");
	}

	@Test
	void touchTargetIs44PixelsAndDoesNotChangeCardLayout() throws Exception {
		String html = Files.readString(LIST);
		String css = Files.readString(CSS);

		assertThat(html).contains("onpointerdown=\"event.stopPropagation()\"");
		assertThat(css).contains(
				".favorite-button",
				"width: 44px",
				"height: 44px",
				"touch-action: manipulation");
	}
}
