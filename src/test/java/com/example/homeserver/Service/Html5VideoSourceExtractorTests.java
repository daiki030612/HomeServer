package com.example.homeserver.Service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;

import org.junit.jupiter.api.Test;

class Html5VideoSourceExtractorTests {
	@Test
	void extractsRelativeHlsSourceAndPageTitle() {
		SafeUrlHttpClient http = mock(SafeUrlHttpClient.class);
		UrlSafetyValidator validator = mock(UrlSafetyValidator.class);
		URI page = URI.create("https://example.com/watch/1");
		URI media = URI.create("https://example.com/media/index.m3u8");
		when(http.getText(page, 2048)).thenReturn(new SafeUrlHttpClient.TextResponse(page,
				"<html><head><title>Sample &amp; Video</title></head>"
				+ "<body><video src='/media/index.m3u8'></video></body></html>", "text/html"));
		when(validator.validate(media)).thenReturn(media);
		Html5VideoSourceExtractor extractor = new Html5VideoSourceExtractor(http, validator, 2048);

		VideoSourceExtractor.ExtractedVideoSource result = extractor.extract(page);

		assertEquals("Sample & Video", result.title());
		assertEquals(media, result.mediaUri());
		assertEquals(VideoSourceExtractor.MediaKind.HLS, result.kind());
	}

	@Test
	void reportsMissingVideoSourceWithoutExposingPageContents() {
		SafeUrlHttpClient http = mock(SafeUrlHttpClient.class);
		UrlSafetyValidator validator = mock(UrlSafetyValidator.class);
		URI page = URI.create("https://example.com/watch/1");
		when(http.getText(page, 2048)).thenReturn(new SafeUrlHttpClient.TextResponse(
				page, "<html><title>Nothing here</title></html>", "text/html"));
		Html5VideoSourceExtractor extractor = new Html5VideoSourceExtractor(http, validator, 2048);

		VideoUrlImportException exception = assertThrows(VideoUrlImportException.class,
				() -> extractor.extract(page));
		assertEquals(VideoUrlImportException.Reason.SOURCE_NOT_FOUND, exception.getReason());
	}
}
