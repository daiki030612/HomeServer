package com.example.homeserver.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MissAvVideoSourceExtractor implements VideoSourceExtractor {
	private static final Set<String> SUPPORTED_HOSTS = Set.of("missav.live");
	private static final int MAX_SYMBOLS = 4096;
	private static final Pattern PACKED_SCRIPT = Pattern.compile(
			"(?s)eval\\s*\\(\\s*function\\s*\\(\\s*p\\s*,\\s*a\\s*,\\s*c\\s*,\\s*k\\s*,\\s*e\\s*,\\s*d\\s*\\)"
			+ "\\s*\\{.*?}\\s*\\(\\s*'((?:\\\\.|[^'\\\\])*)'\\s*,\\s*(\\d{1,2})\\s*,\\s*(\\d{1,4})\\s*,\\s*"
			+ "'((?:\\\\.|[^'\\\\])*)'\\.split\\(\\s*'\\|'\\s*\\)");
	private static final Pattern PACKED_TOKEN = Pattern.compile("\\b[0-9a-z]+\\b");
	private static final Pattern SOURCE_ASSIGNMENT = Pattern.compile(
			"(?i)\\b(source1280|source842|source)\\s*=\\s*(['\"])(.*?)\\2");
	private static final Pattern OG_TITLE = Pattern.compile(
			"(?is)<meta\\b(?=[^>]*(?:property|name)\\s*=\\s*['\"]og:title['\"])(?=[^>]*content\\s*=\\s*['\"]([^'\"]+)['\"])[^>]*>");
	private static final Pattern TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");

	private final SafeUrlHttpClient http;
	private final UrlSafetyValidator validator;
	private final long maxPageBytes;
	private final String userAgent;

	public MissAvVideoSourceExtractor(SafeUrlHttpClient http, UrlSafetyValidator validator,
			@Value("${video.url-import.max-page-bytes:2097152}") long maxPageBytes,
			@Value("${video.url-import.user-agent:Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0 Safari/537.36}") String userAgent) {
		this.http = http;
		this.validator = validator;
		this.maxPageBytes = maxPageBytes;
		this.userAgent = userAgent;
	}

	@Override
	public boolean supports(URI pageUri) {
		return pageUri != null && pageUri.getHost() != null
				&& SUPPORTED_HOSTS.contains(pageUri.getHost().toLowerCase(Locale.ROOT));
	}

	@Override
	public ExtractedVideoSource extract(URI pageUri) {
		SafeUrlHttpClient.TextResponse page = http.getText(pageUri, maxPageBytes);
		Sources sources = parseSources(page.body());
		URI mediaUri = firstSafeHls(sources.source(), sources.source842(), sources.source1280());
		if (mediaUri == null) {
			throw new VideoUrlImportException(VideoUrlImportException.Reason.SOURCE_NOT_FOUND,
					"このページから動画URLを取得できませんでした。");
		}
		String title = firstMatch(OG_TITLE, page.body());
		if (title == null || title.isBlank()) title = firstMatch(TITLE, page.body());
		if (title == null || title.isBlank()) title = "URL import - " + page.finalUri().getHost();
		return new ExtractedVideoSource(decodeHtml(title).trim(), mediaUri, MediaKind.HLS,
				new VideoSourceRequestContext(userAgent, pageUri));
	}

	Sources parseSources(String html) {
		if (html == null || html.isBlank()) return Sources.EMPTY;
		Matcher packed = PACKED_SCRIPT.matcher(html);
		while (packed.find()) {
			String payload = unescapeJavascriptString(packed.group(1));
			String symbolsValue = unescapeJavascriptString(packed.group(4));
			if (payload == null || symbolsValue == null) continue;
			int radix;
			int count;
			try {
				radix = Integer.parseInt(packed.group(2));
				count = Integer.parseInt(packed.group(3));
			} catch (NumberFormatException ignored) {
				continue;
			}
			String[] symbols = symbolsValue.split("\\|", -1);
			if (radix < 2 || radix > 36 || count < 0 || count > MAX_SYMBOLS || count > symbols.length) continue;
			Sources sources = extractAssignments(unpack(payload, radix, count, symbols));
			if (sources.hasAny()) return sources;
		}
		return Sources.EMPTY;
	}

	private String unpack(String payload, int radix, int count, String[] symbols) {
		Matcher tokens = PACKED_TOKEN.matcher(payload);
		StringBuffer unpacked = new StringBuffer(payload.length());
		while (tokens.find()) {
			String replacement = tokens.group();
			try {
				int index = Integer.parseInt(replacement, radix);
				if (index >= 0 && index < count && !symbols[index].isEmpty()) replacement = symbols[index];
			} catch (NumberFormatException ignored) {
				// Literal words that are not radix tokens remain unchanged.
			}
			tokens.appendReplacement(unpacked, Matcher.quoteReplacement(replacement));
		}
		tokens.appendTail(unpacked);
		return unpacked.toString();
	}

	private Sources extractAssignments(String unpacked) {
		String source = null;
		String source842 = null;
		String source1280 = null;
		Matcher assignment = SOURCE_ASSIGNMENT.matcher(unpacked);
		while (assignment.find()) {
			switch (assignment.group(1).toLowerCase(Locale.ROOT)) {
			case "source" -> source = assignment.group(3);
			case "source842" -> source842 = assignment.group(3);
			case "source1280" -> source1280 = assignment.group(3);
			default -> { }
			}
		}
		return new Sources(source, source842, source1280);
	}

	private URI firstSafeHls(String... candidates) {
		for (String candidate : candidates) {
			if (candidate == null || candidate.isBlank()) continue;
			try {
				URI uri = new URI(candidate.trim());
				if (!uri.getPath().toLowerCase(Locale.ROOT).endsWith(".m3u8")) continue;
				return validator.validate(uri);
			} catch (URISyntaxException | VideoUrlImportException ignored) {
				// Invalid and unsafe packed values are ignored; later sources remain eligible.
			}
		}
		return null;
	}

	private String unescapeJavascriptString(String value) {
		StringBuilder result = new StringBuilder(value.length());
		for (int i = 0; i < value.length(); i++) {
			char current = value.charAt(i);
			if (current != '\\') {
				result.append(current);
				continue;
			}
			if (++i >= value.length()) return null;
			char escaped = value.charAt(i);
			switch (escaped) {
			case '\\', '\'', '"' -> result.append(escaped);
			case 'n' -> result.append('\n');
			case 'r' -> result.append('\r');
			case 't' -> result.append('\t');
			default -> result.append(escaped);
			}
		}
		return result.toString();
	}

	private String firstMatch(Pattern pattern, String html) {
		Matcher matcher = pattern.matcher(html);
		return matcher.find() ? matcher.group(1).replaceAll("(?is)<[^>]+>", " ") : null;
	}

	private String decodeHtml(String value) {
		return value.replace("&amp;", "&").replace("&quot;", "\"")
				.replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">");
	}

	record Sources(String source, String source842, String source1280) {
		private static final Sources EMPTY = new Sources(null, null, null);
		boolean hasAny() { return source != null || source842 != null || source1280 != null; }
	}
}
