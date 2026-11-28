package com.example.homeserver.Service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import org.springframework.stereotype.Service;

@Service
public class ThumbnailService {

	public String createThumbnail(String videoPath, String outputPath) {
		Path output = Paths.get(outputPath).toAbsolutePath().normalize();
		Path parent = output.getParent();

		if (parent == null) {
			throw new IllegalArgumentException("Thumbnail output directory is invalid: " + output);
		}

		Path temporaryOutput = parent.resolve(
				"." + output.getFileName() + "." + UUID.randomUUID() + ".tmp.jpg");
		Process process = null;
		boolean outputInstalled = false;

		try {
			Files.createDirectories(parent);
			ProcessBuilder builder = new ProcessBuilder(
					"ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-ss", "0", "-i", videoPath,
                    "-map", "0:V:0",
					"-frames:v", "1", "-y", temporaryOutput.toString());
			builder.redirectErrorStream(true);
			process = startProcess(builder);
            var diagnostic = FfmpegProcessRunner.readLimited(process.getInputStream());

			if (!process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Thumbnail decoding timed out");
            }
            int result = process.exitValue();
            String detail = diagnostic.join();
			if (result != 0) {
				throw new IllegalStateException("FFmpeg exited with code " + result + ": " + detail);
			}

			validateThumbnail(temporaryOutput);
			moveIntoPlace(temporaryOutput, output);
			outputInstalled = true;
			validateThumbnail(output);
			return output.toString();
		} catch (InterruptedException e) {
			if (process != null) {
				process.destroyForcibly();
			}
			Thread.currentThread().interrupt();
			deleteIncompleteThumbnail(temporaryOutput, e);
			if (outputInstalled) {
				deleteIncompleteThumbnail(output, e);
			}
			throw new IllegalStateException("Thumbnail generation was interrupted", e);
		} catch (Exception e) {
			deleteIncompleteThumbnail(temporaryOutput, e);
			if (outputInstalled) {
				deleteIncompleteThumbnail(output, e);
			}
			throw new IllegalStateException("Thumbnail generation failed", e);
		}
	}

	protected Process startProcess(ProcessBuilder builder) throws IOException {
		return builder.start();
	}

	private void validateThumbnail(Path thumbnail) throws IOException {
		if (!Files.isRegularFile(thumbnail) || Files.size(thumbnail) == 0) {
			throw new IOException("Thumbnail was not created or is empty: " + thumbnail);
		}
	}

	private void moveIntoPlace(Path source, Path target) throws IOException {
		try {
			Files.move(source, target,
					StandardCopyOption.ATOMIC_MOVE,
					StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private void deleteIncompleteThumbnail(Path thumbnail, Exception originalFailure) {
		try {
			Files.deleteIfExists(thumbnail);
		} catch (IOException cleanupFailure) {
			originalFailure.addSuppressed(cleanupFailure);
		}
	}
}
