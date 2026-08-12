package com.example.homeserver.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.springframework.stereotype.Service;

@Service
public class VideoRemuxService {

	private static final Duration REMUX_TIMEOUT = Duration.ofMinutes(30);

	private final FfmpegProcessRunner processRunner;

	public VideoRemuxService(FfmpegProcessRunner processRunner) {
		this.processRunner = processRunner;
	}

	public void remux(Path source, Path output, Path storageRoot, int videoStreamIndex) {
		if (videoStreamIndex < 0) {
			throw new IllegalArgumentException("Video stream index must not be negative");
		}
		Path verifiedSource = validateExisting(source, storageRoot);
		Path verifiedOutput = validateOutput(output, storageRoot);

		List<String> command = List.of(
				"ffmpeg", "-nostdin", "-hide_banner", "-y",
				"-i", verifiedSource.toString(),
				"-map", "0:" + videoStreamIndex, "-map", "0:a:0?",
				"-c", "copy", "-movflags", "+faststart",
				verifiedOutput.toString());

		FfmpegProcessRunner.ProcessResult result = processRunner.run(command, REMUX_TIMEOUT);
		if (result.exitCode() != 0) {
			throw new IllegalStateException("FFmpeg remux failed with code " + result.exitCode());
		}

		try {
			if (!Files.isRegularFile(verifiedOutput) || Files.size(verifiedOutput) == 0) {
				throw new IllegalStateException("FFmpeg did not produce a valid MP4 file");
			}
		} catch (java.io.IOException e) {
			throw new IllegalStateException("Remux output could not be verified", e);
		}
	}

	private Path validateExisting(Path path, Path root) {
		try {
			Path verifiedRoot = root.toAbsolutePath().normalize().toRealPath();
			Path verified = path.toAbsolutePath().normalize().toRealPath();
			if (!verified.startsWith(verifiedRoot) || !Files.isRegularFile(verified)) {
				throw new IllegalArgumentException("Remux source is outside storage");
			}
			return verified;
		} catch (java.io.IOException e) {
			throw new IllegalArgumentException("Remux source could not be validated", e);
		}
	}

	private Path validateOutput(Path path, Path root) {
		try {
			Path verifiedRoot = root.toAbsolutePath().normalize().toRealPath();
			Path parent = path.toAbsolutePath().normalize().getParent();
			if (parent == null || !parent.toRealPath().startsWith(verifiedRoot)) {
				throw new IllegalArgumentException("Remux output is outside storage");
			}
			return path.toAbsolutePath().normalize();
		} catch (java.io.IOException e) {
			throw new IllegalArgumentException("Remux output could not be validated", e);
		}
	}
}
