package com.example.homeserver.Service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SafeUrlHttpClientRequestContextTests {
	@TempDir
	Path directory;

	@Test
	void sendsOnlyTypedHeadersAndKeepsThemAcrossRedirects() throws Exception {
		UrlSafetyValidator validator = mock(UrlSafetyValidator.class);
		HttpClient client = mock(HttpClient.class);
		URI initial = URI.create("https://media.example/master.m3u8?token=secret");
		URI redirected = URI.create("https://cdn.example/master.m3u8?token=other-secret");
		URI referer = URI.create("https://missav.live/ja/ipzz-640");
		when(validator.validate(initial)).thenReturn(initial);
		when(validator.validate(redirected)).thenReturn(redirected);
		ArrayDeque<HttpResponse<java.io.InputStream>> responses = new ArrayDeque<>();
		responses.add(response(302, Map.of("Location", List.of(redirected.toString())), ""));
		responses.add(response(200, Map.of("Content-Type", List.of("application/vnd.apple.mpegurl")), "#EXTM3U"));
		List<HttpRequest> requests = new ArrayList<>();
		when(client.send(any(HttpRequest.class), anyInputStreamHandler())).thenAnswer(invocation -> {
			requests.add(invocation.getArgument(0, HttpRequest.class));
			return responses.removeFirst();
		});
		VideoSourceRequestContext context = new VideoSourceRequestContext("Test Browser/1.0", referer);

		new SafeUrlHttpClient(validator, client, "Default Agent/1.0").getText(initial, 1024,
				context, SafeUrlHttpClient.ImportStage.HLS_PLAYLIST);

		assertEquals(2, requests.size());
		for (HttpRequest request : requests) {
			assertEquals("Test Browser/1.0", request.headers().firstValue("User-Agent").orElseThrow());
			assertEquals(referer.toString(), request.headers().firstValue("Referer").orElseThrow());
			assertEquals("application/vnd.apple.mpegurl,application/x-mpegURL;q=0.9,*/*;q=0.5",
					request.headers().firstValue("Accept").orElseThrow());
			assertEquals(3, request.headers().map().size());
		}
		verify(validator).validate(initial);
		verify(validator).validate(redirected);
	}

	@Test
	void ordinaryDownloadUsesConfiguredAgentWithoutReferer() throws Exception {
		UrlSafetyValidator validator = mock(UrlSafetyValidator.class);
		HttpClient client = mock(HttpClient.class);
		URI media = URI.create("https://media.example/video.mp4");
		when(validator.validate(media)).thenReturn(media);
		List<HttpRequest> requests = new ArrayList<>();
		when(client.send(any(HttpRequest.class), anyInputStreamHandler())).thenAnswer(invocation -> {
			requests.add(invocation.getArgument(0, HttpRequest.class));
			return response(200, Map.of("Content-Type", List.of("video/mp4")), "video");
		});

		new SafeUrlHttpClient(validator, client, "Configured Agent/2.0")
				.download(media, directory.resolve("video.mp4"), 1024);

		HttpRequest request = requests.getFirst();
		assertEquals("Configured Agent/2.0", request.headers().firstValue("User-Agent").orElseThrow());
		assertFalse(request.headers().firstValue("Referer").isPresent());
		assertFalse(request.headers().firstValue("Range").isPresent());
		assertFalse(request.headers().firstValue("Accept-Language").isPresent());
		assertFalse(request.headers().firstValue("Sec-Fetch-Dest").isPresent());
		assertFalse(request.headers().firstValue("sec-ch-ua").isPresent());
	}

	@Test
	void tokyoMotionMediaHeadersSurviveRedirectToCdn() throws Exception {
		UrlSafetyValidator validator = mock(UrlSafetyValidator.class);
		HttpClient client = mock(HttpClient.class);
		URI initial = URI.create("https://www.tokyomotion.net/vsrc/sd/13a5fcc5364b6dce1517");
		URI redirected = URI.create("https://www41.tokyomotion.net/video/hash/iphone/3475574.mp4");
		URI referer = URI.create("https://www.tokyomotion.net/");
		when(validator.validate(initial)).thenReturn(initial);
		when(validator.validate(redirected)).thenReturn(redirected);
		ArrayDeque<HttpResponse<InputStream>> responses = new ArrayDeque<>();
		responses.add(response(302, Map.of("Location", List.of(redirected.toString())), ""));
		responses.add(response(200, Map.of("Content-Type", List.of("video/mp4")), "video"));
		List<HttpRequest> requests = new ArrayList<>();
		when(client.send(any(HttpRequest.class), anyInputStreamHandler())).thenAnswer(invocation -> {
			requests.add(invocation.getArgument(0, HttpRequest.class));
			return responses.removeFirst();
		});
		VideoSourceRequestContext context = new VideoSourceRequestContext(
				"Mozilla/5.0 Fixture", referer, true, true, true);

		new SafeUrlHttpClient(validator, client, "Default Agent/1.0").download(initial,
				directory.resolve("tokyomotion.mp4"), 1024, context, SafeUrlHttpClient.ImportStage.MEDIA);

		assertEquals(2, requests.size());
		assertEquals("www.tokyomotion.net", requests.getFirst().uri().getHost());
		assertEquals("www41.tokyomotion.net", requests.getLast().uri().getHost());
		for (HttpRequest request : requests) {
			assertEquals("GET", request.method());
			assertEquals("Mozilla/5.0 Fixture", request.headers().firstValue("User-Agent").orElseThrow());
			assertEquals(referer.toString(), request.headers().firstValue("Referer").orElseThrow());
			assertEquals("*/*", request.headers().firstValue("Accept").orElseThrow());
			assertEquals("ja,en-US;q=0.9,en;q=0.8",
					request.headers().firstValue("Accept-Language").orElseThrow());
			assertEquals("bytes=0-", request.headers().firstValue("Range").orElseThrow());
			assertEquals("video", request.headers().firstValue("Sec-Fetch-Dest").orElseThrow());
			assertEquals("no-cors", request.headers().firstValue("Sec-Fetch-Mode").orElseThrow());
			assertEquals("same-site", request.headers().firstValue("Sec-Fetch-Site").orElseThrow());
			assertEquals("?1", request.headers().firstValue("sec-ch-ua-mobile").orElseThrow());
			assertEquals("\"Android\"", request.headers().firstValue("sec-ch-ua-platform").orElseThrow());
			assertTrue(request.headers().firstValue("sec-ch-ua").orElseThrow().contains("Chromium"));
		}
		verify(validator).validate(initial);
		verify(validator).validate(redirected);
	}

	@Test
	void rejectsHeaderLineInjection() {
		assertThrows(IllegalArgumentException.class,
				() -> new VideoSourceRequestContext("Browser/1.0\r\nX-Injected: yes", URI.create("https://example.com")));
		assertThrows(IllegalArgumentException.class,
				() -> new SafeUrlHttpClient(mock(UrlSafetyValidator.class), mock(HttpClient.class),
						"Browser/1.0\nX-Injected: yes"));
	}

	@Test
	void sharedBudgetPreventsParallelAggregateLimitOverrun() throws Exception {
		UrlSafetyValidator validator = mock(UrlSafetyValidator.class);
		HttpClient client = mock(HttpClient.class);
		URI first = URI.create("https://media.example/first.ts");
		URI second = URI.create("https://media.example/second.ts");
		when(validator.validate(any(URI.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(client.send(any(HttpRequest.class), anyInputStreamHandler())).thenAnswer(invocation ->
				response(200, Map.of("Content-Type", List.of("video/mp2t")), "123456"));
		SafeUrlHttpClient safe = new SafeUrlHttpClient(validator, client, "Test Agent/1.0");
		SafeUrlHttpClient.SharedDownloadBudget budget = new SafeUrlHttpClient.SharedDownloadBudget(10);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<Boolean> one = executor.submit(() -> downloadWithinBudget(safe, first,
					directory.resolve("first.ts"), budget));
			Future<Boolean> two = executor.submit(() -> downloadWithinBudget(safe, second,
					directory.resolve("second.ts"), budget));

			assertTrue(one.get() ^ two.get());
			assertEquals(6, budget.consumedBytes());
			assertTrue(budget.consumedBytes() <= budget.limitBytes());
		} finally {
			executor.shutdownNow();
		}
		verify(validator).validate(first);
		verify(validator).validate(second);
	}

	@Test
	void failedAttemptRollsBackOnlyItsSharedBudgetBytes() throws Exception {
		UrlSafetyValidator validator = mock(UrlSafetyValidator.class);
		HttpClient client = mock(HttpClient.class);
		URI media = URI.create("https://media.example/retry.ts");
		when(validator.validate(media)).thenReturn(media);
		ArrayDeque<HttpResponse<InputStream>> responses = new ArrayDeque<>();
		responses.add(response(200, Map.of(), new InputStream() {
			private boolean first = true;
			@Override public int read() throws IOException { throw new IOException("use bulk read"); }
			@Override public int read(byte[] buffer, int offset, int length) throws IOException {
				if (!first) throw new IOException("connection reset");
				first = false;
				byte[] bytes = "123456".getBytes(StandardCharsets.UTF_8);
				System.arraycopy(bytes, 0, buffer, offset, bytes.length);
				return bytes.length;
			}
		}));
		responses.add(response(200, Map.of(), new ByteArrayInputStream("123456".getBytes(StandardCharsets.UTF_8))));
		when(client.send(any(HttpRequest.class), anyInputStreamHandler())).thenAnswer(invocation ->
				responses.removeFirst());
		SafeUrlHttpClient safe = new SafeUrlHttpClient(validator, client, "Test Agent/1.0");
		SafeUrlHttpClient.SharedDownloadBudget budget = new SafeUrlHttpClient.SharedDownloadBudget(10);

		assertThrows(VideoUrlImportException.class, () -> safe.download(media, directory.resolve("first.ts"),
				100, VideoSourceRequestContext.EMPTY, SafeUrlHttpClient.ImportStage.HLS_RESOURCE, budget));
		assertEquals(0, budget.consumedBytes());

		safe.download(media, directory.resolve("second.ts"), 100, VideoSourceRequestContext.EMPTY,
				SafeUrlHttpClient.ImportStage.HLS_RESOURCE, budget);
		assertEquals(6, budget.consumedBytes());
	}

	private boolean downloadWithinBudget(SafeUrlHttpClient safe, URI uri, Path target,
			SafeUrlHttpClient.SharedDownloadBudget budget) {
		try {
			safe.download(uri, target, 100, VideoSourceRequestContext.EMPTY,
					SafeUrlHttpClient.ImportStage.HLS_RESOURCE, budget);
			return true;
		} catch (VideoUrlImportException expected) {
			return false;
		}
	}

	@SuppressWarnings("unchecked")
	private HttpResponse.BodyHandler<java.io.InputStream> anyInputStreamHandler() {
		return any(HttpResponse.BodyHandler.class);
	}

	@SuppressWarnings("unchecked")
	private HttpResponse<java.io.InputStream> response(int status, Map<String, List<String>> headers, String body) {
		return response(status, headers, new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
	}

	private HttpResponse<java.io.InputStream> response(int status, Map<String, List<String>> headers,
			InputStream body) {
		HttpResponse<java.io.InputStream> response = mock(HttpResponse.class);
		when(response.statusCode()).thenReturn(status);
		when(response.headers()).thenReturn(HttpHeaders.of(headers, (name, value) -> true));
		when(response.body()).thenReturn(body);
		return response;
	}
}
