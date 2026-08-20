package com.example.homeserver.Service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.OrderUtils;

class TokyoMotionVideoSourceExtractorTests {
	private static final URI WATCH = URI.create("https://www.tokyomotion.net/video/3475574/example");
	private static final URI EMBED = URI.create("https://www.tokyomotion.net/embed/0123456789abcdef0123");
	private static final URI MEDIA = URI.create(
			"https://cdn.example.net/video/iphone/fixture.mp4?token=test&expires=1");
	private static final String USER_AGENT = "Fixture browser/1.0";

	@Test
	void recognizesOnlyExactTokyoMotionPageHosts() {
		TokyoMotionVideoSourceExtractor extractor = extractor(mock(SafeUrlHttpClient.class), validating());

		assertTrue(extractor.supports(URI.create("https://tokyomotion.net/video/1")));
		assertTrue(extractor.supports(URI.create("https://www.tokyomotion.net/embed/abc")));
		assertFalse(extractor.supports(URI.create("https://tokyomotion.net.evil.example/video/1")));
		assertFalse(extractor.supports(URI.create("ftp://www.tokyomotion.net/video/1")));
	}

	@Test
	void followsSameSiteEmbedAndPrefersMp4WithRequiredRequestContext() throws IOException {
		SafeUrlHttpClient http = mock(SafeUrlHttpClient.class);
		String watchHtml = fixture("tokyomotion-watch.html");
		String embedHtml = fixture("tokyomotion-embed.html");
		VideoSourceRequestContext initialContext = new VideoSourceRequestContext(USER_AGENT,
				URI.create("https://www.tokyomotion.net/"));
		when(http.getText(eq(WATCH), eq(2048L), eq(initialContext), eq(SafeUrlHttpClient.ImportStage.PAGE)))
				.thenReturn(new SafeUrlHttpClient.TextResponse(WATCH, watchHtml, "text/html"));
		when(http.getText(eq(EMBED), eq(2048L), any(VideoSourceRequestContext.class),
				eq(SafeUrlHttpClient.ImportStage.PAGE)))
				.thenReturn(new SafeUrlHttpClient.TextResponse(EMBED, embedHtml, "text/html"));

		VideoSourceExtractor.ExtractedVideoSource result = extractor(http, validating()).extract(WATCH);

		assertEquals("Fixture TokyoMotion video", result.title());
		assertEquals(MEDIA, result.mediaUri());
		assertEquals(VideoSourceExtractor.MediaKind.MP4, result.kind());
		assertEquals(URI.create("https://images.example.net/posters/fixture.jpg"), result.thumbnailUri());
		assertEquals(USER_AGENT, result.requestContext().userAgent());
		assertEquals(URI.create("https://www.tokyomotion.net/"), result.requestContext().referer());
		verify(http).getText(eq(EMBED), eq(2048L),
				eq(new VideoSourceRequestContext(USER_AGENT, WATCH)), eq(SafeUrlHttpClient.ImportStage.PAGE));
	}

	@Test
	void usesHlsWhenNoMp4CandidateExists() {
		SafeUrlHttpClient http = mock(SafeUrlHttpClient.class);
		String html = "<title>HLS title</title><script>video_url='https:\\/\\/cdn.example.net\\/master.m3u8'</script>";
		when(http.getText(eq(WATCH), eq(2048L), any(VideoSourceRequestContext.class),
				eq(SafeUrlHttpClient.ImportStage.PAGE)))
				.thenReturn(new SafeUrlHttpClient.TextResponse(WATCH, html, "text/html"));

		VideoSourceExtractor.ExtractedVideoSource result = extractor(http, validating()).extract(WATCH);

		assertEquals(URI.create("https://cdn.example.net/master.m3u8"), result.mediaUri());
		assertEquals(VideoSourceExtractor.MediaKind.HLS, result.kind());
	}

	@Test
	void ignoresBlobAndUnsafePrivateCandidates() {
		SafeUrlHttpClient http = mock(SafeUrlHttpClient.class);
		UrlSafetyValidator validator = mock(UrlSafetyValidator.class);
		URI privateMedia = URI.create("http://127.0.0.1/private.mp4");
		when(validator.validate(privateMedia)).thenThrow(new VideoUrlImportException(
				VideoUrlImportException.Reason.INVALID_URL, "unsafe"));
		String html = "<video src='blob:https://www.tokyomotion.net/id'></video>"
				+ "<script>video_url='http://127.0.0.1/private.mp4'</script>";
		when(http.getText(eq(WATCH), eq(2048L), any(VideoSourceRequestContext.class),
				eq(SafeUrlHttpClient.ImportStage.PAGE)))
				.thenReturn(new SafeUrlHttpClient.TextResponse(WATCH, html, "text/html"));

		VideoUrlImportException error = assertThrows(VideoUrlImportException.class,
				() -> extractor(http, validator).extract(WATCH));

		assertEquals(VideoUrlImportException.Reason.SOURCE_NOT_FOUND, error.getReason());
		verify(validator).validate(privateMedia);
	}

	@Test
	void failsSafelyWhenPlayerMarkupHasNoMediaUrl() {
		SafeUrlHttpClient http = mock(SafeUrlHttpClient.class);
		when(http.getText(eq(WATCH), eq(2048L), any(VideoSourceRequestContext.class),
				eq(SafeUrlHttpClient.ImportStage.PAGE)))
				.thenReturn(new SafeUrlHttpClient.TextResponse(WATCH, "<title>Unavailable</title>", "text/html"));

		VideoUrlImportException error = assertThrows(VideoUrlImportException.class,
				() -> extractor(http, validating()).extract(WATCH));

		assertEquals(VideoUrlImportException.Reason.SOURCE_NOT_FOUND, error.getReason());
		assertEquals("TokyoMotionの動画URLを取得できませんでした。", error.getMessage());
	}

	@Test
	void runsBeforeGenericHtml5Extractor() {
		int specific = OrderUtils.getOrder(TokyoMotionVideoSourceExtractor.class, Integer.MAX_VALUE);
		int generic = OrderUtils.getOrder(Html5VideoSourceExtractor.class, Integer.MAX_VALUE);
		assertTrue(specific < generic);
	}

	private TokyoMotionVideoSourceExtractor extractor(SafeUrlHttpClient http, UrlSafetyValidator validator) {
		return new TokyoMotionVideoSourceExtractor(http, validator, 2048, USER_AGENT);
	}

	private UrlSafetyValidator validating() {
		UrlSafetyValidator validator = mock(UrlSafetyValidator.class);
		when(validator.validate(any(URI.class))).thenAnswer(invocation -> invocation.getArgument(0));
		return validator;
	}

	private String fixture(String name) throws IOException {
		try (var input = getClass().getResourceAsStream("/fixtures/" + name)) {
			if (input == null) throw new IOException("Missing fixture: " + name);
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
