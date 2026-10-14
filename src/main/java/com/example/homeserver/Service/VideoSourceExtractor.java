package com.example.homeserver.Service;

import java.net.URI;

public interface VideoSourceExtractor {
	boolean supports(URI pageUri);
	ExtractedVideoSource extract(URI pageUri);

	record ExtractedVideoSource(String title, URI mediaUri, MediaKind kind,
			VideoSourceRequestContext requestContext, URI thumbnailUri) {
		public ExtractedVideoSource(String title, URI mediaUri, MediaKind kind) {
			this(title, mediaUri, kind, VideoSourceRequestContext.EMPTY, null);
		}

		public ExtractedVideoSource(String title, URI mediaUri, MediaKind kind,
				VideoSourceRequestContext requestContext) {
			this(title, mediaUri, kind, requestContext, null);
		}

		public ExtractedVideoSource {
			if (requestContext == null) requestContext = VideoSourceRequestContext.EMPTY;
		}
	}
	enum MediaKind { MP4, HLS }
}
