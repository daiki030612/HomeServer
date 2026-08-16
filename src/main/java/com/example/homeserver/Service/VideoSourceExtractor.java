package com.example.homeserver.Service;

import java.net.URI;

public interface VideoSourceExtractor {
	boolean supports(URI pageUri);
	ExtractedVideoSource extract(URI pageUri);

	record ExtractedVideoSource(String title, URI mediaUri, MediaKind kind) {}
	enum MediaKind { MP4, HLS }
}
