package com.example.homeserver.Service;

public record AudioStreamMetadata(
		String codec,
		String codecTag,
		String profile,
		int sampleRate,
		int channels,
		String channelLayout,
		long bitrate) {
}
