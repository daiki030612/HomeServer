package com.example.homeserver.Service;

import java.io.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LargeVideoRangeTests {
    @TempDir Path root;
    @Test void rangeBeyondFourGiBAndFullStreamUseBoundedBuffers() throws Exception {
        Path file = root.resolve("large-av1.mp4");
        long offset = 4L * 1024 * 1024 * 1024 + 17;
        try (var random = new RandomAccessFile(file.toFile(), "rw")) {
            random.setLength(offset + 4);
            random.seek(offset); random.write(new byte[]{1, 2, 3, 4});
        }
        var service = new VideoStreamService();
        var partial = service.stream(file, "bytes=" + offset + "-");
        var output = new ByteArrayOutputStream();
        partial.getBody().writeTo(output);
        assertArrayEquals(new byte[]{1,2,3,4}, output.toByteArray());
        assertEquals(4, partial.getHeaders().getContentLength());
        var complete = service.stream(file, null);
        assertEquals(offset + 4, complete.getHeaders().getContentLength());
        long[] count = {0};
        complete.getBody().writeTo(new OutputStream() {
            public void write(int value) { count[0]++; }
            public void write(byte[] buffer, int start, int length) {
                assertTrue(length <= 64 * 1024);
                count[0] += length;
            }
        });
        assertEquals(offset + 4, count[0]);
    }
}
