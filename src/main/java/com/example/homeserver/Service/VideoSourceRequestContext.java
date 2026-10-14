package com.example.homeserver.Service;

import java.net.URI;
import java.util.Locale;

public record VideoSourceRequestContext(String userAgent, URI referer,
		boolean initialByteRange, boolean acceptAnyMedia, boolean browserMediaHeaders) {
	public static final VideoSourceRequestContext EMPTY = new VideoSourceRequestContext(null, null, false, false, false);

	public VideoSourceRequestContext(String userAgent, URI referer) {
		this(userAgent, referer, false, false, false);
	}

	public VideoSourceRequestContext(String userAgent, URI referer,
			boolean initialByteRange, boolean acceptAnyMedia) {
		this(userAgent, referer, initialByteRange, acceptAnyMedia, false);
	}

	public VideoSourceRequestContext {
		if (userAgent != null) {
			userAgent = userAgent.trim();
			if (userAgent.isEmpty() || userAgent.length() > 512 || containsLineBreak(userAgent)) {
				throw new IllegalArgumentException("Invalid User-Agent value");
			}
		}
		if (referer != null) {
			String scheme = referer.getScheme() == null ? "" : referer.getScheme().toLowerCase(Locale.ROOT);
			if ((!scheme.equals("http") && !scheme.equals("https")) || referer.getHost() == null
					|| referer.getUserInfo() != null || referer.getFragment() != null
					|| containsLineBreak(referer.toASCIIString())) {
				throw new IllegalArgumentException("Invalid Referer value");
			}
		}
	}

	private static boolean containsLineBreak(String value) {
		return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
	}
}
