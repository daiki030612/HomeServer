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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class VideoProbeService {

	private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(30);

	private final ObjectMapper objectMapper;
	private final Mp4FastStartInspector fastStartInspector;

	@Autowired
	public VideoProbeService(ObjectMapper objectMapper, Mp4FastStartInspector fastStartInspector) {
		this.objectMapper = objectMapper;
		this.fastStartInspector = fastStartInspector;
	}

	public VideoProbeService(ObjectMapper objectMapper) {
		this(objectMapper, new Mp4FastStartInspector());
	}

	public VideoMetadata probe(Path videoPath, Path storageRoot) {
		Path verifiedVideo = validatePath(videoPath, storageRoot);

		try {
			ProbeResult result = executeProbe(verifiedVideo, PROBE_TIMEOUT);
			if (result.exitCode() != 0) {
				throw new IOException("ffprobe exited with code " + result.exitCode());
			}
			return parseAndValidate(result.standardOutput(), verifiedVideo);
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

	private VideoMetadata parseAndValidate(String json, Path videoPath) throws IOException {
		JsonNode root = objectMapper.readTree(json);
		JsonNode streams = root.path("streams");
		if (!streams.isArray()) {
			throw new IOException("ffprobe returned no streams");
		}

		List<VideoStreamMetadata> videoStreams = new ArrayList<>();
		List<AudioStreamMetadata> audioStreams = new ArrayList<>();
		for (JsonNode stream : streams) {
			String type = stream.path("codec_type").asText();
			if ("video".equals(type)) {
				videoStreams.add(parseVideoStream(stream));
			} else if ("audio".equals(type)) {
				audioStreams.add(parseAudioStream(stream));
			}
		}

		if (videoStreams.isEmpty()) {
			throw new IOException("No video stream exists");
		}

		JsonNode format = root.path("format");
		double duration = parseDuration(format.path("duration").asText());
		VideoStreamMetadata primaryVideo = videoStreams.stream()
				.filter(video -> !video.auxiliary())
				.findFirst()
				.orElse(videoStreams.getFirst());

		if (duration <= 0 || primaryVideo.width() <= 0 || primaryVideo.height() <= 0
				|| primaryVideo.codec().isBlank()) {
			throw new IOException("Video metadata is incomplete or invalid");
		}

		return new VideoMetadata(
				new ContainerMetadata(
						format.path("format_name").asText("unknown"),
						format.path("format_long_name").asText("unknown"),
						format.path("tags").path("major_brand").asText(""),
						fileExtension(videoPath),
						duration,
						parseDouble(format.path("start_time").asText(), 0),
						parseLong(format.path("size").asText()),
						parseLong(format.path("bit_rate").asText()),
						fastStartInspector.isFastStart(videoPath)),
				List.copyOf(videoStreams),
				List.copyOf(audioStreams));
	}

	private VideoStreamMetadata parseVideoStream(JsonNode stream) {
		boolean hdrMetadata = hasSideData(stream,
				"mastering display metadata", "content light level metadata", "hdr10+");
		boolean dolbyVision = hasSideData(stream, "dovi", "dolby vision")
				|| stream.path("codec_tag_string").asText("").toLowerCase()
						.matches("dvhe|dvh1");
		JsonNode disposition = stream.path("disposition");
		return new VideoStreamMetadata(
				stream.path("codec_name").asText(""),
				stream.path("codec_tag_string").asText(""),
				stream.path("profile").asText(""),
				stream.path("level").asInt(0),
				stream.path("pix_fmt").asText(""),
				stream.path("width").asInt(0),
				stream.path("height").asInt(0),
				parseFrameRate(stream.path("avg_frame_rate").asText(
						stream.path("r_frame_rate").asText("0/1"))),
				parseLong(stream.path("bit_rate").asText()),
				(int) parseLong(stream.path("bits_per_raw_sample").asText()),
				disposition.path("attached_pic").asInt(0) == 1,
				stream.path("color_space").asText(""),
				stream.path("color_transfer").asText(""),
				stream.path("color_primaries").asText(""),
				hdrMetadata,
				dolbyVision,
				stream.path("index").asInt(0),
				disposition.path("still_image").asInt(0) == 1,
				disposition.path("timed_thumbnails").asInt(0) == 1);
	}

	private boolean hasSideData(JsonNode stream, String... markers) {
		JsonNode sideData = stream.path("side_data_list");
		if (!sideData.isArray()) return false;
		for (JsonNode item : sideData) {
			String type = item.path("side_data_type").asText("").toLowerCase();
			for (String marker : markers) {
				if (type.contains(marker)) return true;
			}
		}
		return false;
	}

	private AudioStreamMetadata parseAudioStream(JsonNode stream) {
		return new AudioStreamMetadata(
				stream.path("codec_name").asText(""),
				stream.path("codec_tag_string").asText(""),
				stream.path("profile").asText(""),
				(int) parseLong(stream.path("sample_rate").asText()),
				stream.path("channels").asInt(0),
				stream.path("channel_layout").asText(""),
				parseLong(stream.path("bit_rate").asText()));
	}

	private double parseFrameRate(String value) {
		String[] parts = value.split("/", 2);
		if (parts.length == 2) {
			double denominator = parseDouble(parts[1], 0);
			return denominator == 0 ? 0 : parseDouble(parts[0], 0) / denominator;
		}
		return parseDouble(value, 0);
	}

	private long parseLong(String value) {
		try {
			return Long.parseLong(value);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private double parseDouble(String value, double fallback) {
		try {
			return Double.parseDouble(value);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private String fileExtension(Path path) {
		String name = path.getFileName().toString();
		int separator = name.lastIndexOf('.');
		return separator < 0 ? "" : name.substring(separator + 1).toLowerCase();
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
