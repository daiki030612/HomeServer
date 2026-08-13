package com.example.homeserver.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

@Component
public class FfmpegProcessRunner {

	private static final int MAX_ERROR_BYTES = 64 * 1024;

	public ProcessResult run(List<String> command, Duration timeout) {
		Process process = null;
		try {
			process = start(command);
			CompletableFuture<String> error = readLimited(process.getErrorStream());
			CompletableFuture<Void> output = discard(process.getInputStream());

			if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
				process.destroyForcibly();
				process.waitFor();
				throw new FfmpegTimeoutException("FFmpeg timed out");
			}

			output.join();
			return new ProcessResult(process.exitValue(), error.join());
		} catch (InterruptedException e) {
			if (process != null) {
				process.destroyForcibly();
			}
			Thread.currentThread().interrupt();
			throw new IllegalStateException("FFmpeg was interrupted", e);
		} catch (IOException e) {
			throw new IllegalStateException("FFmpeg could not be started", e);
		}
	}

	protected Process start(List<String> command) throws IOException {
		return new ProcessBuilder(command).start();
	}

	private CompletableFuture<String> readLimited(InputStream stream) {
		return CompletableFuture.supplyAsync(() -> {
			try (stream) {
				byte[] buffer = new byte[4096];
				byte[] retained = new byte[MAX_ERROR_BYTES];
				int retainedLength = 0;
				int read;
				while ((read = stream.read(buffer)) != -1) {
					int copy = Math.min(read, MAX_ERROR_BYTES - retainedLength);
					if (copy > 0) {
						System.arraycopy(buffer, 0, retained, retainedLength, copy);
						retainedLength += copy;
					}
				}
				return new String(retained, 0, retainedLength, StandardCharsets.UTF_8);
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
		});
	}

	private CompletableFuture<Void> discard(InputStream stream) {
		return CompletableFuture.runAsync(() -> {
			try (stream) {
				stream.transferTo(java.io.OutputStream.nullOutputStream());
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
		});
	}

	public record ProcessResult(int exitCode, String standardError) {
	}

	public static class FfmpegTimeoutException extends RuntimeException {
		public FfmpegTimeoutException(String message) {
			super(message);
		}
	}
}
