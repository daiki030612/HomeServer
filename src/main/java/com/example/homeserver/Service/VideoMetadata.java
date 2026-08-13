package com.example.homeserver.Service;

import java.util.List;

public record VideoMetadata(
		ContainerMetadata container,
		List<VideoStreamMetadata> videoStreams,
		List<AudioStreamMetadata> audioStreams) {

	public String containerFormat() {
		return container.formatName();
	}

	public String videoCodec() {
		return normalVideoStream().codec();
	}

	public List<String> audioCodecs() {
		return audioStreams.stream().map(AudioStreamMetadata::codec).toList();
	}

	public double durationSeconds() {
		return container.durationSeconds();
	}

	public int width() {
		return normalVideoStream().width();
	}

	public int height() {
		return normalVideoStream().height();
	}

	public List<VideoStreamMetadata> normalVideoStreams() {
		return videoStreams.stream().filter(video -> !video.auxiliary()).toList();
	}

	private VideoStreamMetadata normalVideoStream() {
		return normalVideoStreams().getFirst();
	}
}
