package com.example.homeserver.Service;

public record ContainerMetadata(
		String formatName,
		String formatLongName,
		String majorBrand,
		String fileExtension,
		double durationSeconds,
		double startTimeSeconds,
		long sizeBytes,
		long bitrate,
		boolean fastStart) {
}
