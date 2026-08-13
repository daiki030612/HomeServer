package com.example.homeserver.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@Service
public class VideoStreamService {
	private static final int BUFFER_SIZE = 64 * 1024;

	public ResponseEntity<StreamingResponseBody> stream(Path video, String rangeHeader)
			throws IOException {
		long fileSize = Files.size(video);
		if (fileSize <= 0) return ResponseEntity.notFound().build();

		ByteRange range;
		try {
			range = parseRange(rangeHeader, fileSize);
		} catch (IllegalArgumentException e) {
			return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
					.header(HttpHeaders.ACCEPT_RANGES, "bytes")
					.header(HttpHeaders.CONTENT_RANGE, "bytes */" + fileSize)
					.build();
		}

		long contentLength = range.end() - range.start() + 1;
		StreamingResponseBody body = output -> copyRange(video, output, range, contentLength);
		ResponseEntity.BodyBuilder response = ResponseEntity.status(
				range.partial() ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK)
				.header(HttpHeaders.ACCEPT_RANGES, "bytes")
				.contentType(detectMediaType(video))
				.contentLength(contentLength);
		if (range.partial()) {
			response.header(HttpHeaders.CONTENT_RANGE,
					"bytes " + range.start() + "-" + range.end() + "/" + fileSize);
		}
		return response.body(body);
	}

	private ByteRange parseRange(String header, long fileSize) {
		if (header == null || header.isBlank()) return new ByteRange(0, fileSize - 1, false);
		if (!header.startsWith("bytes=") || header.indexOf(',') >= 0) {
			throw new IllegalArgumentException("Unsupported Range header");
		}
		String value = header.substring("bytes=".length()).trim();
		int separator = value.indexOf('-');
		if (separator < 0) throw new IllegalArgumentException("Malformed Range header");

		String startValue = value.substring(0, separator).trim();
		String endValue = value.substring(separator + 1).trim();
		try {
			long start;
			long end;
			if (startValue.isEmpty()) {
				long suffixLength = Long.parseLong(endValue);
				if (suffixLength <= 0) throw new IllegalArgumentException("Invalid suffix range");
				start = Math.max(0, fileSize - suffixLength);
				end = fileSize - 1;
			} else {
				start = Long.parseLong(startValue);
				end = endValue.isEmpty() ? fileSize - 1 : Long.parseLong(endValue);
				if (start < 0 || start >= fileSize || end < start) {
					throw new IllegalArgumentException("Unsatisfiable range");
				}
				end = Math.min(end, fileSize - 1);
			}
			return new ByteRange(start, end, true);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Malformed Range header", e);
		}
	}

	private void copyRange(Path video, OutputStream output, ByteRange range, long length)
			throws IOException {
		try (RandomAccessFile input = new RandomAccessFile(video.toFile(), "r")) {
			input.seek(range.start());
			byte[] buffer = new byte[BUFFER_SIZE];
			long remaining = length;
			while (remaining > 0) {
				int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
				if (read < 0) break;
				output.write(buffer, 0, read);
				remaining -= read;
			}
		}
	}

	private MediaType detectMediaType(Path video) {
		try {
			String contentType = Files.probeContentType(video);
			return contentType == null ? MediaType.APPLICATION_OCTET_STREAM
					: MediaType.parseMediaType(contentType);
		} catch (Exception e) {
			return MediaType.APPLICATION_OCTET_STREAM;
		}
	}

	private record ByteRange(long start, long end, boolean partial) {}
}
