package com.example.homeserver.Service;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.example.homeserver.Entity.Video;
import com.example.homeserver.Repository.VideoRepository;

@Service
public class CustomThumbnailService {
	public static final long MAX_UPLOAD_BYTES = 10L * 1024 * 1024;
	private static final long MAX_PIXELS = 40_000_000L;
	private static final int MAX_EDGE = 1280;
	private static final Map<String, String> CONTENT_TYPES = Map.of(
			"image/jpeg", "jpg", "image/png", "png", "image/webp", "webp");

	private final VideoRepository videoRepository;
	private final Path root;
	private final ConcurrentHashMap<Long, Object> locks = new ConcurrentHashMap<>();

	public CustomThumbnailService(VideoRepository videoRepository,
			@Value("${thumbnail.storage.path}") String thumbnailStoragePath) {
		this.videoRepository = videoRepository;
		this.root = Path.of(thumbnailStoragePath).toAbsolutePath().normalize();
	}

	public Video upload(Long videoId, MultipartFile upload) {
		if (upload == null || upload.isEmpty()) throw new InvalidThumbnailException("画像を選択してください。");
		if (upload.getSize() > MAX_UPLOAD_BYTES) throw new InvalidThumbnailException("画像は10MB以下にしてください。");
		String contentType = normalizeContentType(upload.getContentType());
		String extension = CONTENT_TYPES.get(contentType);
		if (extension == null) throw new InvalidThumbnailException("JPEG、PNG、WebPのみアップロードできます。");

		Object lock = locks.computeIfAbsent(videoId, ignored -> new Object());
		synchronized (lock) {
			Video video = videoRepository.findById(videoId).orElse(null);
			if (video == null) return null;
			return install(video, upload, contentType, extension);
		}
	}

	public Video reset(Long videoId) {
		Object lock = locks.computeIfAbsent(videoId, ignored -> new Object());
		synchronized (lock) {
			Video video = videoRepository.findById(videoId).orElse(null);
			if (video == null) return null;
			String oldName = video.getCustomThumbnailName();
			video.setCustomThumbnailName(null);
			video.setCustomThumbnailContentType(null);
			video.setCustomThumbnailVersion(System.currentTimeMillis());
			Video saved = videoRepository.saveAndFlush(video);
			deleteCustomFile(oldName);
			return saved;
		}
	}

	public ThumbnailResource resolve(Long videoId) {
		Video video = videoRepository.findById(videoId).orElse(null);
		if (video == null) return null;
		if (video.getCustomThumbnailName() != null) {
			Path custom = resolveCustom(video.getCustomThumbnailName(), true);
			if (Files.isRegularFile(custom) && Files.isReadable(custom)) {
				return new ThumbnailResource(custom, video.getCustomThumbnailContentType(), video.getCustomThumbnailVersion());
			}
		}
		Path automatic = resolveAutomatic(video);
		return automatic != null && Files.isRegularFile(automatic) && Files.isReadable(automatic)
				? new ThumbnailResource(automatic, "image/jpeg", null) : null;
	}

	public void deleteForVideo(Video video) {
		if (video != null) deleteCustomFile(video.getCustomThumbnailName());
	}

	private Video install(Video video, MultipartFile upload, String contentType, String extension) {
		Path directory = customDirectory();
		String name = video.getId() + "-" + UUID.randomUUID() + "." + extension;
		Path temporary = resolveCustom("." + name + ".upload", false);
		Path target = resolveCustom(name, false);
		String oldName = video.getCustomThumbnailName();
		try {
			Files.createDirectories(directory);
			ensureSafeDirectory(directory);
			copyLimited(upload, temporary);
			validateAndOptimize(temporary, contentType);
			move(temporary, target);

			video.setCustomThumbnailName(name);
			video.setCustomThumbnailContentType(contentType);
			video.setCustomThumbnailVersion(Instant.now().toEpochMilli());
			try {
				Video saved = videoRepository.saveAndFlush(video);
				deleteCustomFile(oldName);
				return saved;
			} catch (RuntimeException e) {
				Files.deleteIfExists(target);
				throw e;
			}
		} catch (InvalidThumbnailException e) {
			deleteQuietly(temporary);
			deleteQuietly(target);
			throw e;
		} catch (IOException e) {
			deleteQuietly(temporary);
			deleteQuietly(target);
			throw new InvalidThumbnailException("サムネイル画像を保存できませんでした。", e);
		}
	}

	private void validateAndOptimize(Path file, String contentType) throws IOException {
		byte[] header = new byte[12];
		try (InputStream input = Files.newInputStream(file)) {
			if (input.read(header) < header.length) throw new InvalidThumbnailException("画像ファイルが壊れています。");
		}
		if (!matchesMagic(header, contentType)) throw new InvalidThumbnailException("画像形式とContent-Typeが一致しません。");
		if ("image/webp".equals(contentType)) {
			validateWebp(file);
			return;
		}

		BufferedImage source;
		int orientation = "image/jpeg".equals(contentType) ? readExifOrientation(file) : 1;
		try (ImageInputStream stream = ImageIO.createImageInputStream(file.toFile())) {
			var readers = ImageIO.getImageReaders(stream);
			if (!readers.hasNext()) throw new InvalidThumbnailException("画像として読み込めません。");
			ImageReader reader = readers.next();
			try {
				reader.setInput(stream, true, true);
				int width = reader.getWidth(0);
				int height = reader.getHeight(0);
				validateDimensions(width, height);
				source = reader.read(0);
			} finally {
				reader.dispose();
			}
		}
		if (source == null) throw new InvalidThumbnailException("画像として読み込めません。");
		source = applyOrientation(source, orientation);
		if (Math.max(source.getWidth(), source.getHeight()) <= MAX_EDGE && orientation == 1) return;

		double scale = Math.min(1d, (double) MAX_EDGE / Math.max(source.getWidth(), source.getHeight()));
		int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
		int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
		int imageType = "image/png".equals(contentType) ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
		BufferedImage resized = new BufferedImage(width, height, imageType);
		Graphics2D graphics = resized.createGraphics();
		try {
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			graphics.drawImage(source, 0, 0, width, height, null);
		} finally {
			graphics.dispose();
		}
		if (!ImageIO.write(resized, "image/png".equals(contentType) ? "png" : "jpg", file.toFile())) {
			throw new InvalidThumbnailException("画像を最適化できませんでした。");
		}
	}

	private void validateWebp(Path file) throws IOException {
		byte[] bytes = Files.readAllBytes(file);
		if (bytes.length < 30) throw new InvalidThumbnailException("WebP画像が壊れています。");
		int width;
		int height;
		String chunk = new String(bytes, 12, 4, java.nio.charset.StandardCharsets.US_ASCII);
		if ("VP8X".equals(chunk)) {
			width = 1 + little24(bytes, 24);
			height = 1 + little24(bytes, 27);
		} else if ("VP8L".equals(chunk) && bytes[20] == 0x2f) {
			int bits = little32(bytes, 21);
			width = (bits & 0x3fff) + 1;
			height = ((bits >>> 14) & 0x3fff) + 1;
		} else if ("VP8 ".equals(chunk) && bytes.length >= 30 && bytes[23] == (byte) 0x9d
				&& bytes[24] == 0x01 && bytes[25] == 0x2a) {
			width = little16(bytes, 26) & 0x3fff;
			height = little16(bytes, 28) & 0x3fff;
		} else {
			throw new InvalidThumbnailException("WebP画像が壊れています。");
		}
		validateDimensions(width, height);
	}

	private int readExifOrientation(Path file) throws IOException {
		byte[] bytes = Files.readAllBytes(file);
		for (int marker = 2; marker + 4 < bytes.length && (bytes[marker] & 0xff) == 0xff;) {
			int type = bytes[marker + 1] & 0xff;
			if (type == 0xda || type == 0xd9) break;
			int length = big16(bytes, marker + 2);
			if (length < 2 || marker + 2 + length > bytes.length) break;
			int payload = marker + 4;
			if (type == 0xe1 && length >= 16 && matches(bytes, payload, "Exif\0\0")) {
				int tiff = payload + 6;
				boolean little = bytes[tiff] == 'I' && bytes[tiff + 1] == 'I';
				if (!little && !(bytes[tiff] == 'M' && bytes[tiff + 1] == 'M')) return 1;
				int ifd = tiff + read32(bytes, tiff + 4, little);
				if (ifd < tiff || ifd + 2 > marker + 2 + length) return 1;
				int entries = read16(bytes, ifd, little);
				for (int index = 0; index < entries; index++) {
					int entry = ifd + 2 + index * 12;
					if (entry + 12 > marker + 2 + length) break;
					if (read16(bytes, entry, little) == 0x0112) {
						int value = read16(bytes, entry + 8, little);
						return value >= 1 && value <= 8 ? value : 1;
					}
				}
			}
			marker += 2 + length;
		}
		return 1;
	}

	private BufferedImage applyOrientation(BufferedImage source, int orientation) {
		if (orientation == 1) return source;
		int width = source.getWidth();
		int height = source.getHeight();
		boolean swap = orientation >= 5 && orientation <= 8;
		BufferedImage target = new BufferedImage(swap ? height : width, swap ? width : height,
				source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = target.createGraphics();
		try {
			switch (orientation) {
				case 2 -> { graphics.translate(width, 0); graphics.scale(-1, 1); }
				case 3 -> { graphics.translate(width, height); graphics.rotate(Math.PI); }
				case 4 -> { graphics.translate(0, height); graphics.scale(1, -1); }
				case 5 -> { graphics.rotate(Math.PI / 2); graphics.scale(1, -1); }
				case 6 -> { graphics.translate(height, 0); graphics.rotate(Math.PI / 2); }
				case 7 -> { graphics.translate(height, width); graphics.scale(-1, 1); graphics.rotate(Math.PI / 2); }
				case 8 -> { graphics.translate(0, width); graphics.rotate(-Math.PI / 2); }
				default -> { return source; }
			}
			graphics.drawImage(source, 0, 0, null);
		} finally {
			graphics.dispose();
		}
		return target;
	}

	private boolean matches(byte[] bytes, int offset, String expected) {
		byte[] value = expected.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
		if (offset < 0 || offset + value.length > bytes.length) return false;
		for (int i = 0; i < value.length; i++) if (bytes[offset + i] != value[i]) return false;
		return true;
	}

	private int big16(byte[] bytes, int offset) {
		return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
	}

	private int read16(byte[] bytes, int offset, boolean little) {
		return little ? little16(bytes, offset) : big16(bytes, offset);
	}

	private int read32(byte[] bytes, int offset, boolean little) {
		return little ? little32(bytes, offset)
				: (big16(bytes, offset) << 16) | big16(bytes, offset + 2);
	}

	private boolean matchesMagic(byte[] h, String type) {
		return switch (type) {
			case "image/jpeg" -> (h[0] & 0xff) == 0xff && (h[1] & 0xff) == 0xd8 && (h[2] & 0xff) == 0xff;
			case "image/png" -> (h[0] & 0xff) == 0x89 && h[1] == 0x50 && h[2] == 0x4e && h[3] == 0x47
					&& h[4] == 0x0d && h[5] == 0x0a && h[6] == 0x1a && h[7] == 0x0a;
			case "image/webp" -> h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
					&& h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P';
			default -> false;
		};
	}

	private void validateDimensions(int width, int height) {
		if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS) {
			throw new InvalidThumbnailException("画像サイズが大きすぎます。");
		}
	}

	private Path resolveAutomatic(Video video) {
		Path path = video.getThumbnailPath() == null || video.getThumbnailPath().isBlank()
				? root.resolve(video.getThumbnailName()).normalize()
				: Path.of(video.getThumbnailPath()).toAbsolutePath().normalize();
		return isWithinRoot(path, true) ? path : null;
	}

	private Path customDirectory() {
		return root.resolve("custom").normalize();
	}

	private Path resolveCustom(String name, boolean existing) {
		if (name == null || name.isBlank() || !name.matches("[0-9]+-[0-9a-fA-F-]+\\.(jpg|png|webp)") && !name.matches("\\.[0-9]+-[0-9a-fA-F-]+\\.(jpg|png|webp)\\.upload")) {
			throw new IllegalArgumentException("Invalid custom thumbnail name");
		}
		Path path = customDirectory().resolve(name).normalize();
		if (!isWithinRoot(path, existing)) throw new IllegalArgumentException("Custom thumbnail is outside storage");
		return path;
	}

	private boolean isWithinRoot(Path path, boolean existing) {
		try {
			Path verifiedRoot = Files.exists(root) ? root.toRealPath() : root;
			Path verified = existing && Files.exists(path, LinkOption.NOFOLLOW_LINKS)
					? path.toRealPath()
					: (Files.exists(path.getParent()) ? path.getParent().toRealPath().resolve(path.getFileName()).normalize() : path);
			return verified.startsWith(verifiedRoot);
		} catch (IOException e) {
			return false;
		}
	}

	private void ensureSafeDirectory(Path directory) throws IOException {
		if (!directory.toRealPath().startsWith(root.toRealPath())) throw new IOException("Unsafe thumbnail directory");
	}

	private void deleteCustomFile(String name) {
		if (name == null || name.isBlank()) return;
		try {
			Files.deleteIfExists(resolveCustom(name, true));
		} catch (IOException | IllegalArgumentException e) {
			throw new IllegalStateException("カスタムサムネイルを削除できませんでした。", e);
		}
	}

	private void move(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(source, target);
		}
	}

	private void copyLimited(MultipartFile upload, Path target) throws IOException {
		try (InputStream input = new BufferedInputStream(upload.getInputStream());
				var output = Files.newOutputStream(target)) {
			byte[] buffer = new byte[64 * 1024];
			long total = 0;
			int read;
			while ((read = input.read(buffer)) >= 0) {
				total += read;
				if (total > MAX_UPLOAD_BYTES) throw new InvalidThumbnailException("画像は10MB以下にしてください。");
				output.write(buffer, 0, read);
			}
		}
	}

	private void deleteQuietly(Path path) {
		try { Files.deleteIfExists(path); } catch (IOException ignored) { }
	}

	private String normalizeContentType(String value) {
		return value == null ? "" : value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
	}

	private int little16(byte[] b, int offset) {
		return (b[offset] & 0xff) | ((b[offset + 1] & 0xff) << 8);
	}

	private int little24(byte[] b, int offset) {
		return little16(b, offset) | ((b[offset + 2] & 0xff) << 16);
	}

	private int little32(byte[] b, int offset) {
		return little16(b, offset) | (little16(b, offset + 2) << 16);
	}

	public record ThumbnailResource(Path path, String contentType, Long version) { }
}
