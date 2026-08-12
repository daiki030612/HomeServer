package com.example.homeserver.Service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Mp4FastStartInspectorTests {

	@TempDir
	Path temporaryDirectory;

	private final Mp4FastStartInspector inspector = new Mp4FastStartInspector();

	@Test
	void detectsMoovBeforeMediaData() throws Exception {
		Path file = writeAtoms("ftyp", "moov", "mdat");
		assertTrue(inspector.isFastStart(file));
	}

	@Test
	void rejectsMediaDataBeforeMoov() throws Exception {
		Path file = writeAtoms("ftyp", "mdat", "moov");
		assertFalse(inspector.isFastStart(file));
	}

	@Test
	void rejectsMalformedAtomStructure() throws Exception {
		Path file = Files.write(temporaryDirectory.resolve("broken.mp4"), new byte[] { 1, 2, 3 });
		assertFalse(inspector.isFastStart(file));
	}

	private Path writeAtoms(String... types) throws Exception {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		for (String type : types) {
			output.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(8).array());
			output.write(type.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
		}
		return Files.write(temporaryDirectory.resolve("sample.mp4"), output.toByteArray());
	}
}
