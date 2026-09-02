package com.example.homeserver.Service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.mock.web.MockMultipartFile;

import com.example.homeserver.Entity.Video;
import com.example.homeserver.Repository.VideoRepository;

@ExtendWith(MockitoExtension.class)
class CustomThumbnailServiceTests {
	@TempDir Path temporaryDirectory;
	@Mock VideoRepository repository;
	private Path root;
	private CustomThumbnailService service;
	private Video video;

	@BeforeEach
	void setUp() throws Exception {
		root = Files.createDirectory(temporaryDirectory.resolve("thumbnails"));
		service = new CustomThumbnailService(repository, root.toString());
		video = new Video();
		video.setId(7L);
		lenient().when(repository.findById(7L)).thenReturn(Optional.of(video));
		lenient().when(repository.saveAndFlush(any(Video.class))).thenAnswer(invocation -> invocation.getArgument(0));
	}

	@ParameterizedTest
	@ValueSource(strings = { "jpeg", "png" })
	void uploadsDecodedJpegAndPngAndStoresServerManagedPath(String format) throws Exception {
		String contentType = "jpeg".equals(format) ? "image/jpeg" : "image/png";
		Video saved = service.upload(7L, image(format, contentType, 32, 18));

		assertThat(saved.getCustomThumbnailName()).matches("7-[0-9a-f-]+\\.(jpg|png)");
		assertThat(saved.getCustomThumbnailContentType()).isEqualTo(contentType);
		assertThat(root.resolve("custom").resolve(saved.getCustomThumbnailName())).isRegularFile();
	}

	@Test
	void acceptsStructurallyValidWebpWithoutAddingDecoderDependency() throws Exception {
		byte[] webp = minimalWebp(16, 9);
		Video saved = service.upload(7L, new MockMultipartFile("thumbnail", "x.webp", "image/webp", webp));
		assertThat(saved.getCustomThumbnailName()).endsWith(".webp");
	}

	@Test
	void replacementKeepsNewFileAndRemovesOldFile() throws Exception {
		service.upload(7L, image("png", "image/png", 10, 10));
		String oldName = video.getCustomThumbnailName();
		service.upload(7L, image("jpeg", "image/jpeg", 10, 10));
		assertThat(root.resolve("custom").resolve(oldName)).doesNotExist();
		assertThat(root.resolve("custom").resolve(video.getCustomThumbnailName())).isRegularFile();
	}

	@Test
	void resetDeletesOnlyCustomAndFallsBackToAutomaticThumbnail() throws Exception {
		Path automatic = Files.write(root.resolve("auto.jpg"), new byte[] { 1 });
		video.setThumbnailName("auto.jpg");
		video.setThumbnailPath(automatic.toString());
		service.upload(7L, image("png", "image/png", 10, 10));
		Path custom = root.resolve("custom").resolve(video.getCustomThumbnailName());

		service.reset(7L);

		assertNull(video.getCustomThumbnailName());
		assertThat(custom).doesNotExist();
		assertThat(automatic).exists();
		assertThat(service.resolve(7L).path()).isEqualTo(automatic);
	}

	@Test
	void customDisplayTakesPriorityOverAutomaticThumbnail() throws Exception {
		Path automatic = Files.write(root.resolve("auto.jpg"), new byte[] { 1 });
		video.setThumbnailPath(automatic.toString());
		service.upload(7L, image("png", "image/png", 10, 10));
		assertThat(service.resolve(7L).path().getFileName().toString()).isEqualTo(video.getCustomThumbnailName());
		assertThat(service.resolve(7L).contentType()).isEqualTo("image/png");
	}

	@Test
	void rejectsEmptyOversizedInvalidSvgAndFakeMime() throws Exception {
		assertThrows(InvalidThumbnailException.class,
				() -> service.upload(7L, new MockMultipartFile("thumbnail", "x.png", "image/png", new byte[0])));
		assertThrows(InvalidThumbnailException.class,
				() -> service.upload(7L, new MockMultipartFile("thumbnail", "x.png", "image/png", new byte[(int) CustomThumbnailService.MAX_UPLOAD_BYTES + 1])));
		assertThrows(InvalidThumbnailException.class,
				() -> service.upload(7L, new MockMultipartFile("thumbnail", "x.png", "image/png", "invalid".getBytes())));
		assertThrows(InvalidThumbnailException.class,
				() -> service.upload(7L, new MockMultipartFile("thumbnail", "x.svg", "image/svg+xml", "<svg/>".getBytes())));
		assertThrows(InvalidThumbnailException.class,
				() -> service.upload(7L, new MockMultipartFile("thumbnail", "x.png", "image/png", imageBytes("jpeg", 4, 4))));
	}

	@Test
	void nonexistentVideoReturnsNullWithoutWriting() throws Exception {
		when(repository.findById(99L)).thenReturn(Optional.empty());
		assertNull(service.upload(99L, image("png", "image/png", 4, 4)));
		assertThat(root.resolve("custom")).doesNotExist();
	}

	@Test
	void rejectsStoredTraversalReference() {
		video.setCustomThumbnailName("../../outside.jpg");
		assertThrows(IllegalArgumentException.class, () -> service.resolve(7L));
	}

	@Test
	void resizesLargeDecodedImageWithoutUpscalingSmallImages() throws Exception {
		service.upload(7L, image("jpeg", "image/jpeg", 1400, 700));
		BufferedImage result = ImageIO.read(root.resolve("custom").resolve(video.getCustomThumbnailName()).toFile());
		assertThat(result.getWidth()).isEqualTo(1280);
		assertThat(result.getHeight()).isEqualTo(640);
	}

	@Test
	void appliesIphoneExifOrientationBeforeSavingJpeg() throws Exception {
		byte[] jpeg = imageBytes("jpeg", 20, 10);
		byte[] oriented = withExifOrientation(jpeg, 6);
		service.upload(7L, new MockMultipartFile("thumbnail", "iphone.jpg", "image/jpeg", oriented));
		BufferedImage result = ImageIO.read(root.resolve("custom").resolve(video.getCustomThumbnailName()).toFile());
		assertThat(result.getWidth()).isEqualTo(10);
		assertThat(result.getHeight()).isEqualTo(20);
	}

	private MockMultipartFile image(String format, String type, int width, int height) throws Exception {
		return new MockMultipartFile("thumbnail", "photo." + format, type, imageBytes(format, width, height));
	}

	private byte[] imageBytes(String format, int width, int height) throws Exception {
		BufferedImage image = new BufferedImage(width, height,
				"png".equals(format) ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ImageIO.write(image, format, output);
		return output.toByteArray();
	}

	private byte[] minimalWebp(int width, int height) {
		byte[] bytes = new byte[30];
		System.arraycopy("RIFF".getBytes(), 0, bytes, 0, 4);
		System.arraycopy("WEBPVP8X".getBytes(), 0, bytes, 8, 8);
		int w = width - 1, h = height - 1;
		bytes[24] = (byte) w; bytes[25] = (byte) (w >>> 8); bytes[26] = (byte) (w >>> 16);
		bytes[27] = (byte) h; bytes[28] = (byte) (h >>> 8); bytes[29] = (byte) (h >>> 16);
		return bytes;
	}

	private byte[] withExifOrientation(byte[] jpeg, int orientation) throws Exception {
		byte[] payload = new byte[] {
				'E','x','i','f',0,0, 'I','I',42,0, 8,0,0,0,
				1,0, 0x12,0x01, 3,0, 1,0,0,0, (byte) orientation,0,0,0, 0,0,0,0 };
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		output.write(jpeg, 0, 2);
		output.write(0xff); output.write(0xe1);
		int length = payload.length + 2;
		output.write(length >>> 8); output.write(length);
		output.write(payload);
		output.write(jpeg, 2, jpeg.length - 2);
		return output.toByteArray();
	}
}
