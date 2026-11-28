package com.example.homeserver.Service;

import com.example.homeserver.Entity.Video;
import com.example.homeserver.Repository.VideoRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Requires FFmpeg with libsvtav1, libx264, libx265 and AAC. No original media is needed. */
@EnabledIfSystemProperty(named = "mediaIntegrationTests", matches = "true")
class LosslessVideoIntegrationTests {
    @TempDir Path root;
    final FfmpegProcessRunner runner = new FfmpegProcessRunner();
    final VideoProbeService probe = new VideoProbeService(new ObjectMapper());
    final IPhoneSafariCompatibilityService policy = new IPhoneSafariCompatibilityService();

    @ParameterizedTest
    @CsvSource({"av1,mp4", "av1,mkv", "h264,mp4", "h264,mkv", "hevc,mp4", "hevc,mkv"})
    void realUploadPreserves120fpsPacketsDurationThumbnailRangeAndDownload(String codec, String extension) throws Exception {
        Path source = generate(codec, extension, 320, 180);
        Path videos = Files.createDirectory(root.resolve("videos"));
        Path thumbnails = Files.createDirectory(root.resolve("thumbnails"));
        VideoRepository repository = mock(VideoRepository.class);
        var service = new VideoService();
        ReflectionTestUtils.setField(service, "videoRepository", repository);
        ReflectionTestUtils.setField(service, "videoStoragePath", videos.toString());
        ReflectionTestUtils.setField(service, "thumbnailStoragePath", thumbnails.toString());
        ReflectionTestUtils.setField(service, "videoProbeService", probe);
        ReflectionTestUtils.setField(service, "compatibilityService", policy);
        ReflectionTestUtils.setField(service, "videoRemuxService", new VideoRemuxService(runner));
        ReflectionTestUtils.setField(service, "thumbnailService", new ThumbnailService());
        service.upload(new DiskMultipart(source), null);

        var captured = ArgumentCaptor.forClass(Video.class);
        verify(repository).saveAndFlush(captured.capture());
        Video saved = captured.getValue();
        Path output = Path.of(saved.getFilePath());
        assertTrue(output.toString().endsWith(".mp4"));
        assertTrue(Files.size(Path.of(saved.getThumbnailPath())) > 0);
        assertEquals(1, countFiles(videos));
        var before = probe.probe(source, root);
        var after = probe.probe(output, root);
        assertEquals(codec, after.videoCodec());
        assertEquals(120, after.normalVideoStreams().getFirst().framesPerSecond(), 0.15);
        assertEquals(before.durationSeconds(), after.durationSeconds(), 0.05);
        VideoService.validateRemuxTiming(before, after);
        assertEquals(packetHash(source, "v:0"), packetHash(output, "v:0"));
        assertEquals(packetHash(source, "a:0"), packetHash(output, "a:0"));
        assertTimestamps(source, output);
        if (extension.equals("mp4")) assertEquals(-1, Files.mismatch(source, output));

        var response = new VideoStreamService().stream(output, "bytes=16-47");
        assertEquals(206, response.getStatusCode().value());
        assertEquals("video/mp4", response.getHeaders().getContentType().toString());
        var bytes = new ByteArrayOutputStream();
        response.getBody().writeTo(bytes);
        try (var input = new RandomAccessFile(output.toFile(), "r")) {
            input.seek(16);
            byte[] expected = new byte[32]; input.readFully(expected);
            assertArrayEquals(expected, bytes.toByteArray());
        }
        saved.setId(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(saved));
        var download = new MediaDownloadService(service, mock(FolderService.class)).video(1L);
        assertTrue(download.getHeaders().getContentDisposition().getFilename().endsWith(".mp4"));
        bytes.reset(); download.getBody().writeTo(bytes);
        try (var input = Files.newInputStream(output)) {
            assertEquals(hexDigest(input), hexDigest(new ByteArrayInputStream(bytes.toByteArray())));
        }
    }

    @Test void disguisedPlaylistIsRejectedBeforeMediaParsing() throws Exception {
        Path fake = Files.writeString(root.resolve("fake.mp4"), "#EXTM3U\nhttp://example.invalid/video.ts");
        assertThrows(InvalidVideoFileException.class, () -> probe.probe(fake, root));
    }

    @Test void av1Main1080p120IsAcceptedWithoutRemux() throws Exception {
        Path file = generate("av1", "mp4", 1920, 1080);
        var metadata = probe.probe(file, root);
        assertEquals(VideoCompatibilityDecision.PASSTHROUGH, policy.evaluate(metadata));
        assertEquals(120, metadata.normalVideoStreams().getFirst().framesPerSecond(), 0.01);
        new ThumbnailService().createThumbnail(file.toString(), root.resolve("1080p.jpg").toString());
    }

    Path generate(String codec, String extension, int width, int height) throws Exception {
        Path file = root.resolve(codec + "." + extension);
        var args = new ArrayList<>(List.of("ffmpeg", "-nostdin", "-v", "error", "-y",
                "-f", "lavfi", "-i", "color=c=blue:s=" + width + "x" + height + ":r=120",
                "-f", "lavfi", "-i", "sine=frequency=1000:sample_rate=48000",
                "-t", "1", "-c:v", codec.equals("av1") ? "libsvtav1" : codec.equals("h264") ? "libx264" : "libx265",
                "-preset", codec.equals("av1") ? "12" : "ultrafast", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-ac", "2"));
        if (codec.equals("hevc") && extension.equals("mp4")) args.addAll(List.of("-tag:v", "hvc1"));
        args.add(file.toString()); run(args);
        return file;
    }
    void assertTimestamps(Path before, Path after) throws Exception {
        var beforeAudio = timestamps(before, "a:0");
        var afterAudio = timestamps(after, "a:0");
        double commonShift = afterAudio.getFirst() - beforeAudio.getFirst();
        for (String stream : List.of("v:0", "a:0")) {
            var left = timestamps(before, stream);
            var right = timestamps(after, stream);
            assertEquals(left.size(), right.size());
            for (int i = 0; i < left.size(); i++) {
                assertEquals(left.get(i) + commonShift, right.get(i), 0.002,
                        "PTS or audio/video synchronization changed at packet " + i);
            }
        }
    }
    List<Double> timestamps(Path path, String stream) throws Exception {
        Path json = root.resolve(UUID.randomUUID() + ".json");
        var process = new ProcessBuilder("ffprobe", "-v", "error", "-select_streams", stream,
                "-show_packets", "-show_entries", "packet=pts_time", "-of", "json", path.toString())
                .redirectOutput(json.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly(); fail("ffprobe timeout");
        }
        assertEquals(0, process.exitValue());
        var values = new ArrayList<Double>();
        for (var packet : new ObjectMapper().readTree(json.toFile()).path("packets")) {
            values.add(packet.path("pts_time").asDouble());
        }
        return values;
    }

    String packetHash(Path path, String stream) throws Exception {
        Path hash = root.resolve(UUID.randomUUID() + ".hash");
        run(List.of("ffmpeg", "-nostdin", "-v", "error", "-y", "-i", path.toString(),
                "-map", "0:" + stream, "-c", "copy", "-f", "hash", "-hash", "sha256", hash.toString()));
        return Files.readString(hash);
    }
    void run(List<String> command) {
        var result = runner.run(command, java.time.Duration.ofMinutes(2));
        assertEquals(0, result.exitCode(), result.standardError());
    }
    static long countFiles(Path path) throws IOException {
        try (var files = Files.list(path)) { return files.count(); }
    }
    static String hexDigest(InputStream input) throws Exception {
        try (input) {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            input.transferTo(new java.security.DigestOutputStream(OutputStream.nullOutputStream(), digest));
            return HexFormat.of().formatHex(digest.digest());
        }
    }
    record DiskMultipart(Path source) implements MultipartFile {
        public String getName() { return "file"; }
        public String getOriginalFilename() { return source.getFileName().toString(); }
        public String getContentType() { return "application/octet-stream"; }
        public boolean isEmpty() { return false; }
        public long getSize() { try { return Files.size(source); } catch (IOException e) { throw new UncheckedIOException(e); } }
        public byte[] getBytes() { throw new AssertionError("Must never buffer an upload in memory"); }
        public InputStream getInputStream() throws IOException { return Files.newInputStream(source); }
        public void transferTo(File destination) throws IOException { Files.copy(source, destination.toPath()); }
    }
}
