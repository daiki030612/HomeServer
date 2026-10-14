package com.example.homeserver.Service;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

import org.springframework.stereotype.Component;

@Component
public class UrlSafetyValidator {
	public URI validate(String rawUrl) {
		try {
			return validate(URI.create(rawUrl == null ? "" : rawUrl.trim()));
		} catch (IllegalArgumentException e) {
			throw invalid(e);
		}
	}

	public URI validate(URI uri) {
		if (uri == null || uri.getScheme() == null || uri.getHost() == null
				|| uri.getUserInfo() != null || uri.getFragment() != null) {
			throw invalid(null);
		}
		String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
		if (!scheme.equals("http") && !scheme.equals("https")) {
			throw invalid(null);
		}
		if (uri.getPort() < -1 || uri.getPort() == 0) {
			throw invalid(null);
		}
		String host = uri.getHost().toLowerCase(Locale.ROOT);
		if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")) {
			throw invalid(null);
		}
		try {
			InetAddress[] addresses = InetAddress.getAllByName(host);
			if (addresses.length == 0) throw invalid(null);
			for (InetAddress address : addresses) {
				if (isBlocked(address)) throw invalid(null);
			}
		} catch (UnknownHostException e) {
			throw invalid(e);
		}
		return uri.normalize();
	}

	boolean isBlocked(InetAddress address) {
		if (address.isAnyLocalAddress() || address.isLoopbackAddress()
				|| address.isLinkLocalAddress() || address.isSiteLocalAddress()
				|| address.isMulticastAddress()) return true;
		byte[] bytes = address.getAddress();
		if (address instanceof Inet4Address) {
			int first = bytes[0] & 0xff;
			int second = bytes[1] & 0xff;
			return first == 0 || first == 10 || first == 127
					|| (first == 100 && second >= 64 && second <= 127)
					|| (first == 169 && second == 254)
					|| (first == 172 && second >= 16 && second <= 31)
					|| (first == 192 && second == 168)
					|| first >= 224;
		}
		if (address instanceof Inet6Address) {
			int first = bytes[0] & 0xff;
			return (first & 0xfe) == 0xfc;
		}
		return true;
	}

	private VideoUrlImportException invalid(Throwable cause) {
		String message = "公開HTTP(S) URLを入力してください。ローカルまたはプライベートネットワーク宛てURLは利用できません。";
		return cause == null
				? new VideoUrlImportException(VideoUrlImportException.Reason.INVALID_URL, message)
				: new VideoUrlImportException(VideoUrlImportException.Reason.INVALID_URL, message, cause);
	}
}
