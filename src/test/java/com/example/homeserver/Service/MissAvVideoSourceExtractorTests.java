package com.example.homeserver.Service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.OrderUtils;

class MissAvVideoSourceExtractorTests {
	private static final URI PAGE = URI.create("https://missav.live/ja/ipzz-640");
	private static final URI MASTER = URI.create(
			"https://surrit.com/70bbdfc6-de49-40ff-a3fe-79cdbf79618d/playlist.m3u8");
	private static final URI DIRECT = URI.create(
			"https://surrit.com/70bbdfc6-de49-40ff-a3fe-79cdbf79618d/720p/video.m3u8");
	private static final String PACKED = "eval(function(p,a,c,k,e,d){return p;}('"
			+ "e=\\'8://7.6/5-4-3-2-1/d.0\\';c=\\'8://7.6/5-4-3-2-1/a/9.0\\';"
			+ "b=\\'8://7.6/5-4-3-2-1/a/9.0\\';',15,15,"
			+ "'m3u8|79cdbf79618d|a3fe|40ff|de49|70bbdfc6|com|surrit|https|video|720p|"
			+ "source1280|source842|playlist|source'.split('|'),0,{}))";

	@Test
	void unpacksAllMissAvSourcesWithoutExecutingJavascript() {
		MissAvVideoSourceExtractor extractor = extractor(mock(SafeUrlHttpClient.class),
				mock(UrlSafetyValidator.class));

		MissAvVideoSourceExtractor.Sources sources = extractor.parseSources(PACKED);

		assertEquals(MASTER.toString(), sources.source());
		assertEquals(DIRECT.toString(), sources.source842());
		assertEquals(DIRECT.toString(), sources.source1280());
	}

	@Test
	void prefersMasterSourceAndValidatesIt() {
		SafeUrlHttpClient http = mock(SafeUrlHttpClient.class);
		UrlSafetyValidator validator = mock(UrlSafetyValidator.class);
		when(http.getText(PAGE, 2048)).thenReturn(new SafeUrlHttpClient.TextResponse(PAGE,
				"<meta property=\"og:title\" content=\"IPZZ-640 &amp; Sample\"><script>"
				+ PACKED + "</script>", "text/html"));
		when(validator.validate(MASTER)).thenReturn(MASTER);
		MissAvVideoSourceExtractor extractor = extractor(http, validator);

		VideoSourceExtractor.ExtractedVideoSource result = extractor.extract(PAGE);

		assertEquals(MASTER, result.mediaUri());
		assertEquals(VideoSourceExtractor.MediaKind.HLS, result.kind());
		assertEquals("IPZZ-640 & Sample", result.title());
		verify(validator).validate(MASTER);
		verify(validator, never()).validate(DIRECT);
	}

	@Test
	void fallsBackToDirectHlsWhenMasterIsAbsent() {
		SafeUrlHttpClient http = mock(SafeUrlHttpClient.class);
		UrlSafetyValidator validator = mock(UrlSafetyValidator.class);
		String withoutMaster = PACKED.replace("e=\\'8://7.6/5-4-3-2-1/d.0\\';", "");
		when(http.getText(PAGE, 2048)).thenReturn(new SafeUrlHttpClient.TextResponse(
				PAGE, "<script>" + withoutMaster + "</script>", "text/html"));
		when(validator.validate(DIRECT)).thenReturn(DIRECT);

		VideoSourceExtractor.ExtractedVideoSource result = extractor(http, validator).extract(PAGE);

		assertEquals(DIRECT, result.mediaUri());
		verify(validator).validate(DIRECT);
	}

	@Test
	void malformedPackerAndArbitraryJavascriptFailSafely() {
		SafeUrlHttpClient http = mock(SafeUrlHttpClient.class);
		UrlSafetyValidator validator = mock(UrlSafetyValidator.class);
		String script = "<script>globalThis.compromised=true; eval(alert('not packer'));</script>";
		when(http.getText(PAGE, 2048)).thenReturn(
				new SafeUrlHttpClient.TextResponse(PAGE, script, "text/html"));

		VideoUrlImportException error = assertThrows(VideoUrlImportException.class,
				() -> extractor(http, validator).extract(PAGE));

		assertEquals(VideoUrlImportException.Reason.SOURCE_NOT_FOUND, error.getReason());
		verify(validator, never()).validate(org.mockito.ArgumentMatchers.any(URI.class));
	}

	@Test
	void supportsOnlyExplicitMissAvHost() {
		MissAvVideoSourceExtractor extractor = extractor(mock(SafeUrlHttpClient.class),
				mock(UrlSafetyValidator.class));

		assertTrue(extractor.supports(PAGE));
		assertFalse(extractor.supports(URI.create("https://example.com/ja/ipzz-640")));
		assertFalse(extractor.supports(URI.create("https://missav.live.example.com/ja/ipzz-640")));
	}

	@Test
	void isOrderedBeforeGenericHtmlExtractor() {
		int missAvOrder = OrderUtils.getOrder(MissAvVideoSourceExtractor.class, Integer.MAX_VALUE);
		int genericOrder = OrderUtils.getOrder(Html5VideoSourceExtractor.class, Integer.MAX_VALUE);

		assertTrue(missAvOrder < genericOrder);
	}

	private MissAvVideoSourceExtractor extractor(SafeUrlHttpClient http, UrlSafetyValidator validator) {
		return new MissAvVideoSourceExtractor(http, validator, 2048);
	}
}
