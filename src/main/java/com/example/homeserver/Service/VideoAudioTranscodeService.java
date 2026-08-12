package com.example.homeserver.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.springframework.stereotype.Service;

@Service
public class VideoAudioTranscodeService {

	private static final Duration TRANSCODE_TIMEOUT = Duration.ofHours(3);

	private final FfmpegProcessRunner processRunner;

	public VideoAudioTranscodeService(FfmpegProcessRunner processRunner) {
		this.processRunner = processRunner;
	}

	public void transcodeAudio(Path source, Path output, Path storageRoot) {
		Path verifiedSource = validateExisting(source, storageRoot);
		Path verifiedOutput = validateOutput(output, storageRoot);

		List<String> command = List.of(
				"ffmpeg", "-nostdin", "-hide_banner", "-y",
				"-i", verifiedSource.toString(),
				"-map", "0:v:0", "-map", "0:a:0",
				"-c:v", "copy",
				"-c:a", "aac", "-profile:a", "aac_low",
				"-ar", "48000", "-ac", "2",
				"-movflags", "+faststart",
				verifiedOutput.toString());

		FfmpegProcessRunner.ProcessResult result = processRunner.run(command, TRANSCODE_TIMEOUT);
		if (result.exitCode() != 0) {
			throw new IllegalStateException(
					"FFmpeg audio transcode failed with code " + result.exitCode());
		}

		try {
			if (!Files.isRegularFile(verifiedOutput) || Files.size(verifiedOutput) == 0) {
				throw new IllegalStateException("FFmpeg did not produce a valid MP4 file");
			}
		} catch (java.io.IOException e) {
			throw new IllegalStateException("Audio transcode output could not be verified", e);
		}
	}

	private Path validateExisting(Path path, Path root) {
		try {
			Path verifiedRoot = root.toAbsolutePath().normalize().toRealPath();
			Path verified = path.toAbsolutePath().normalize().toRealPath();
			if (!verified.startsWith(verifiedRoot) || !Files.isRegularFile(verified)) {
				throw new IllegalArgumentException("Audio transcode source is outside storage");
			}
			return verified;
		} catch (java.io.IOException e) {
			throw new IllegalArgumentException("Audio transcode source could not be validated", e);
		}
	}

	private Path validateOutput(Path path, Path root) {
		try {
			Path verifiedRoot = root.toAbsolutePath().normalize().toRealPath();
			Path normalized = path.toAbsolutePath().normalize();
			Path parent = normalized.getParent();
			if (parent == null || !parent.toRealPath().startsWith(verifiedRoot)) {
				throw new IllegalArgumentException("Audio transcode output is outside storage");
			}
			return normalized;
		} catch (java.io.IOException e) {
			throw new IllegalArgumentException("Audio transcode output could not be validated", e);
		}
	}
}
