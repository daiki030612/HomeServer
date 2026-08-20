package com.example.homeserver.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TokyoMotionVideoSourceExtractor implements VideoSourceExtractor {
	private static final Set<String> SUPPORTED_HOSTS = Set.of("tokyomotion.net", "www.tokyomotion.net");
	private static final Pattern EMBED_IFRAME = Pattern.compile(
			"(?is)<iframe\\b[^>]*?\\bsrc\\s*=\\s*['\"]([^'\"]+)['\"]");
	private static final Pattern PLAYER_MEDIA = Pattern.compile(
			"(?is)(?:video_url(?:_hd|_text)?|file|src)\\s*[:=]\\s*['\"]([^'\"]+)['\"]");
	private static final Pattern HTML5_MEDIA = Pattern.compile(
			"(?is)<(?:video|source)\\b[^>]*?\\bsrc\\s*=\\s*['\"]([^'\"]+)['\"]");
	private static final Pattern OG_TITLE = Pattern.compile(
			"(?is)<meta\\b(?=[^>]*(?:property|name)\\s*=\\s*['\"]og:title['\"])(?=[^>]*content\\s*=\\s*['\"]([^'\"]+)['\"])[^>]*>");
	private static final Pattern OG_IMAGE = Pattern.compile(
			"(?is)<meta\\b(?=[^>]*(?:property|name)\\s*=\\s*['\"]og:image['\"])(?=[^>]*content\\s*=\\s*['\"]([^'\"]+)['\"])[^>]*>");
	private static final Pattern POSTER = Pattern.compile(
			"(?is)<video\\b[^>]*?\\bposter\\s*=\\s*['\"]([^'\"]+)['\"]");
	private static final Pattern TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");

	private final SafeUrlHttpClient http;
	private final UrlSafetyValidator validator;
	private final long maxPageBytes;
	private final String userAgent;

	public TokyoMotionVideoSourceExtractor(SafeUrlHttpClient http, UrlSafetyValidator validator,
			@Value("${video.url-import.max-page-bytes:2097152}") long maxPageBytes,
			@Value("${video.url-import.user-agent:Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0 Safari/537.36}") String userAgent) {
		this.http = http;
		this.validator = validator;
		this.maxPageBytes = maxPageBytes;
		this.userAgent = userAgent;
	}

	@Override
	public boolean supports(URI pageUri) {
		if (pageUri == null || pageUri.getHost() == null || pageUri.getScheme() == null) return false;
		String scheme = pageUri.getScheme().toLowerCase(Locale.ROOT);
		return (scheme.equals("http") || scheme.equals("https"))
				&& SUPPORTED_HOSTS.contains(pageUri.getHost().toLowerCase(Locale.ROOT));
	}

	@Override
	public ExtractedVideoSource extract(URI pageUri) {
		VideoSourceRequestContext pageContext = new VideoSourceRequestContext(userAgent, origin(pageUri));
		SafeUrlHttpClient.TextResponse page = http.getText(pageUri, maxPageBytes, pageContext,
				SafeUrlHttpClient.ImportStage.PAGE);
		String title = extractTitle(page.body());
		URI thumbnailUri = findThumbnail(page.finalUri(), page.body());
		PageContent mediaPage = new PageContent(page.finalUri(), page.body());
		URI embedUri = findEmbedUri(page.finalUri(), page.body());
		if (embedUri != null && !sameResource(embedUri, page.finalUri())) {
			VideoSourceRequestContext embedContext = new VideoSourceRequestContext(userAgent, page.finalUri());
			SafeUrlHttpClient.TextResponse embed = http.getText(embedUri, maxPageBytes, embedContext,
					SafeUrlHttpClient.ImportStage.PAGE);
			mediaPage = new PageContent(embed.finalUri(), embed.body());
			if (title == null || title.isBlank()) title = extractTitle(embed.body());
			if (thumbnailUri == null) thumbnailUri = findThumbnail(embed.finalUri(), embed.body());
		}

		MediaCandidate media = findMedia(mediaPage);
		if (media == null) {
			throw new VideoUrlImportException(VideoUrlImportException.Reason.SOURCE_NOT_FOUND,
					"TokyoMotionの動画URLを取得できませんでした。");
		}
		if (title == null || title.isBlank()) title = "TokyoMotion video";
		return new ExtractedVideoSource(cleanTitle(title), media.uri(), media.kind(),
				new VideoSourceRequestContext(userAgent, origin(page.finalUri()), true, true), thumbnailUri);
	}

	private URI findThumbnail(URI pageUri, String html) {
		for (Pattern pattern : List.of(OG_IMAGE, POSTER)) {
			String value = firstMatch(pattern, html);
			URI candidate = resolve(pageUri, decode(value));
			if (candidate == null) continue;
			try {
				return validator.validate(candidate);
			} catch (VideoUrlImportException ignored) {
				// A thumbnail is optional and can never make the video import fail.
			}
		}
		return null;
	}

	private URI findEmbedUri(URI baseUri, String html) {
		Matcher matcher = EMBED_IFRAME.matcher(html == null ? "" : html);
		while (matcher.find()) {
			URI candidate = resolve(baseUri, decode(matcher.group(1)));
			if (candidate == null || !supports(candidate)
					|| !candidate.getPath().toLowerCase(Locale.ROOT).startsWith("/embed/")) continue;
			try {
				return validator.validate(candidate);
			} catch (VideoUrlImportException ignored) {
				// Unsafe iframe URLs are never fetched; later valid embeds remain eligible.
			}
		}
		return null;
	}

	private MediaCandidate findMedia(PageContent page) {
		List<String> candidates = new ArrayList<>();
		collect(PLAYER_MEDIA, page.html(), candidates);
		collect(HTML5_MEDIA, page.html(), candidates);
		MediaCandidate hls = null;
		for (String value : candidates) {
			URI uri = resolve(page.uri(), decodeJavascriptUrl(value));
			if (uri == null) continue;
			String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
			MediaKind kind;
			if (path.endsWith(".mp4")) kind = MediaKind.MP4;
			else if (path.endsWith(".m3u8")) kind = MediaKind.HLS;
			else continue;
			try {
				MediaCandidate safe = new MediaCandidate(validator.validate(uri), kind);
				if (kind == MediaKind.MP4) return safe;
				if (hls == null) hls = safe;
			} catch (VideoUrlImportException ignored) {
				// Every extracted CDN URL is subject to the same SSRF validation.
			}
		}
		return hls;
	}

	private URI resolve(URI base, String value) {
		if (value == null || value.isBlank() || value.regionMatches(true, 0, "blob:", 0, 5)) return null;
		try {
			URI reference = new URI(value.trim());
			if (reference.isAbsolute() && !isHttp(reference)) return null;
			return base.resolve(reference);
		} catch (URISyntaxException | IllegalArgumentException ignored) {
			return null;
		}
	}

	private boolean isHttp(URI uri) {
		return uri.getScheme() != null && (uri.getScheme().equalsIgnoreCase("http")
				|| uri.getScheme().equalsIgnoreCase("https"));
	}

	private URI origin(URI uri) {
		try {
			return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), "/", null, null);
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException("Invalid TokyoMotion page URI", e);
		}
	}

	private boolean sameResource(URI left, URI right) {
		return left.normalize().equals(right.normalize());
	}

	private String extractTitle(String html) {
		String title = firstMatch(OG_TITLE, html);
		if (title == null || title.isBlank()) title = firstMatch(TITLE, html);
		return title == null ? null : decode(title.replaceAll("(?is)<[^>]+>", " ")).trim();
	}

	private String cleanTitle(String title) {
		return title.replaceFirst("(?i)\\s*[-|]\\s*TokyoMotion\\s*$", "").trim();
	}

	private void collect(Pattern pattern, String html, List<String> output) {
		Matcher matcher = pattern.matcher(html == null ? "" : html);
		while (matcher.find()) output.add(matcher.group(1));
	}

	private String firstMatch(Pattern pattern, String html) {
		Matcher matcher = pattern.matcher(html == null ? "" : html);
		return matcher.find() ? matcher.group(1) : null;
	}

	private String decodeJavascriptUrl(String value) {
		return decode(value).replace("\\/", "/").replace("\\u0026", "&").replace("\\u002F", "/")
				.replace("\\u002f", "/");
	}

	private String decode(String value) {
		return value == null ? null : value.replace("&amp;", "&").replace("&quot;", "\"")
				.replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">");
	}

	private record PageContent(URI uri, String html) {}
	private record MediaCandidate(URI uri, MediaKind kind) {}
}
