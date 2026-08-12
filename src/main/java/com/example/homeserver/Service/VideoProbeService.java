package com.example.homeserver.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class VideoProbeService {

	private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(30);

	private final ObjectMapper objectMapper;

	public VideoProbeService(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public VideoMetadata probe(Path videoPath, Path storageRoot) {
		Path verifiedVideo = validatePath(videoPath, storageRoot);

		try {
			ProbeResult result = executeProbe(verifiedVideo, PROBE_TIMEOUT);
			if (result.exitCode() != 0) {
				throw new IOException("ffprobe exited with code " + result.exitCode());
			}
			return parseAndValidate(result.standardOutput());
		} catch (Exception e) {
			if (e instanceof InvalidVideoFileException invalidVideo) {
				throw invalidVideo;
			}
			throw new InvalidVideoFileException(e);
		}
	}

	protected ProbeResult executeProbe(Path videoPath, Duration timeout) throws Exception {
		Process process = new ProcessBuilder(
				"ffprobe", "-v", "error",
				"-print_format", "json",
				"-show_format", "-show_streams",
				videoPath.toString())
				.start();

		CompletableFuture<String> output = readStream(process.getInputStream());
		CompletableFuture<String> error = readStream(process.getErrorStream());

		if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
			process.destroyForcibly();
			process.waitFor();
			throw new IOException("ffprobe timed out");
		}

		return new ProbeResult(process.exitValue(), output.get(), error.get());
	}

	private CompletableFuture<String> readStream(java.io.InputStream stream) {
		return CompletableFuture.supplyAsync(() -> {
			try (stream) {
				return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
		});
	}

	private VideoMetadata parseAndValidate(String json) throws IOException {
		JsonNode root = objectMapper.readTree(json);
		JsonNode streams = root.path("streams");
		if (!streams.isArray()) {
			throw new IOException("ffprobe returned no streams");
		}

		JsonNode videoStream = null;
		List<String> audioCodecs = new ArrayList<>();
		for (JsonNode stream : streams) {
			String type = stream.path("codec_type").asText();
			if (videoStream == null && "video".equals(type)) {
				videoStream = stream;
			} else if ("audio".equals(type)) {
				audioCodecs.add(stream.path("codec_name").asText("unknown"));
			}
		}

		if (videoStream == null) {
			throw new IOException("No video stream exists");
		}

		JsonNode format = root.path("format");
		double duration = parseDuration(format.path("duration").asText());
		int width = videoStream.path("width").asInt(0);
		int height = videoStream.path("height").asInt(0);
		String videoCodec = videoStream.path("codec_name").asText();

		if (duration <= 0 || width <= 0 || height <= 0 || videoCodec.isBlank()) {
			throw new IOException("Video metadata is incomplete or invalid");
		}

		return new VideoMetadata(
				format.path("format_name").asText("unknown"),
				videoCodec,
				List.copyOf(audioCodecs),
				duration,
				width,
				height);
	}

	private double parseDuration(String duration) throws IOException {
		try {
			double value = Double.parseDouble(duration);
			if (!Double.isFinite(value)) {
				throw new NumberFormatException("not finite");
			}
			return value;
		} catch (NumberFormatException e) {
			throw new IOException("Invalid video duration", e);
		}
	}

	private Path validatePath(Path videoPath, Path storageRoot) {
		try {
			Path verifiedRoot = storageRoot.toAbsolutePath().normalize().toRealPath();
			Path verifiedVideo = videoPath.toAbsolutePath().normalize().toRealPath();
			if (!verifiedVideo.startsWith(verifiedRoot)
					|| !Files.isRegularFile(verifiedVideo)
					|| Files.size(verifiedVideo) == 0) {
				throw new InvalidVideoFileException();
			}
			return verifiedVideo;
		} catch (IOException e) {
			throw new InvalidVideoFileException(e);
		}
	}

	protected record ProbeResult(int exitCode, String standardOutput, String standardError) {
	}
}
