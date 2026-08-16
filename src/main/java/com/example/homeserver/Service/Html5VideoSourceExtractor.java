package com.example.homeserver.Service;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class Html5VideoSourceExtractor implements VideoSourceExtractor {
	private static final Pattern TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
	private static final Pattern VIDEO_SRC = Pattern.compile(
			"(?is)<(?:video|source)\\b[^>]*?\\bsrc\\s*=\\s*['\"]([^'\"]+)['\"]");
	private static final Pattern META_VIDEO = Pattern.compile(
			"(?is)<meta\\b(?=[^>]*(?:property|name)\\s*=\\s*['\"](?:og:video(?::url)?|twitter:player:stream)['\"])[^>]*?content\\s*=\\s*['\"]([^'\"]+)['\"][^>]*>");

	private final SafeUrlHttpClient http;
	private final UrlSafetyValidator validator;
	private final long maxPageBytes;

	public Html5VideoSourceExtractor(SafeUrlHttpClient http, UrlSafetyValidator validator,
			@Value("${video.url-import.max-page-bytes:2097152}") long maxPageBytes) {
		this.http = http;
		this.validator = validator;
		this.maxPageBytes = maxPageBytes;
	}

	@Override
	public boolean supports(URI pageUri) {
		String path = pageUri.getPath().toLowerCase(Locale.ROOT);
		return path.endsWith(".mp4") || path.endsWith(".m3u8")
				|| pageUri.getScheme().equalsIgnoreCase("http")
				|| pageUri.getScheme().equalsIgnoreCase("https");
	}

	@Override
	public ExtractedVideoSource extract(URI pageUri) {
		String path = pageUri.getPath().toLowerCase(Locale.ROOT);
		if (path.endsWith(".mp4")) return direct(pageUri, MediaKind.MP4);
		if (path.endsWith(".m3u8")) return direct(pageUri, MediaKind.HLS);

		SafeUrlHttpClient.TextResponse page = http.getText(pageUri, maxPageBytes);
		String candidate = firstMatch(META_VIDEO, page.body());
		if (candidate == null) candidate = firstMatch(VIDEO_SRC, page.body());
		if (candidate == null) {
			throw new VideoUrlImportException(VideoUrlImportException.Reason.SOURCE_NOT_FOUND,
					"ページから動画URLを見つけられませんでした。標準のvideo/sourceまたはog:videoに対応しています。");
		}
		URI mediaUri = validator.validate(page.finalUri().resolve(decode(candidate)));
		MediaKind kind = mediaUri.getPath().toLowerCase(Locale.ROOT).endsWith(".m3u8")
				? MediaKind.HLS : MediaKind.MP4;
		String title = decode(firstMatch(TITLE, page.body()));
		return new ExtractedVideoSource(title == null || title.isBlank() ? hostTitle(page.finalUri()) : title.trim(),
				mediaUri, kind);
	}

	private ExtractedVideoSource direct(URI uri, MediaKind kind) {
		String name = uri.getPath().substring(uri.getPath().lastIndexOf('/') + 1);
		return new ExtractedVideoSource(name.isBlank() ? hostTitle(uri) : name, validator.validate(uri), kind);
	}

	private String firstMatch(Pattern pattern, String value) {
		Matcher matcher = pattern.matcher(value);
		return matcher.find() ? matcher.group(1).replaceAll("(?is)<[^>]+>", " ").trim() : null;
	}

	private String decode(String value) {
		return value == null ? null : value.replace("&amp;", "&").replace("&quot;", "\"")
				.replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">");
	}

	private String hostTitle(URI uri) {
		return "URL import - " + uri.getHost();
	}
}
