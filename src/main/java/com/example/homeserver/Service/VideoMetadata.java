package com.example.homeserver.Service;

import java.util.List;

public record VideoMetadata(
		String containerFormat,
		String videoCodec,
		List<String> audioCodecs,
		double durationSeconds,
		int width,
		int height) {
}
