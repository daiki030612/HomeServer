package com.example.homeserver.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.stereotype.Component;

@Component
public class Mp4FastStartInspector {

	public boolean isFastStart(Path file) {
		try (InputStream input = Files.newInputStream(file)) {
			long offset = 0;
			long fileSize = Files.size(file);
			byte[] header = new byte[8];

			while (offset + 8 <= fileSize) {
				if (input.readNBytes(header, 0, 8) != 8) {
					return false;
				}

				long atomSize = Integer.toUnsignedLong(
						ByteBuffer.wrap(header, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt());
				String atomType = new String(header, 4, 4, StandardCharsets.US_ASCII);
				long headerSize = 8;

				if (atomSize == 1) {
					byte[] extended = input.readNBytes(8);
					if (extended.length != 8) {
						return false;
					}
					atomSize = ByteBuffer.wrap(extended).order(ByteOrder.BIG_ENDIAN).getLong();
					headerSize = 16;
				} else if (atomSize == 0) {
					atomSize = fileSize - offset;
				}

				if (atomSize < headerSize || offset + atomSize > fileSize) {
					return false;
				}
				if ("moov".equals(atomType)) {
					return true;
				}
				if ("mdat".equals(atomType)) {
					return false;
				}

				input.skipNBytes(atomSize - headerSize);
				offset += atomSize;
			}
		} catch (IOException | RuntimeException e) {
			return false;
		}

		return false;
	}
}
