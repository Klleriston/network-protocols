package dev.klleriston.netlab.wire;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

class TcpFramingTest {

    @Test
    void readsFrameDeliveredOneByteAtATime() throws IOException {
        byte[] frame = MessageCodec.encode(TestMessage.of(7, 9, 16));
        var bytes = new ByteArrayOutputStream();
        TcpFraming.write(bytes, frame);
        byte[] wire = bytes.toByteArray();
        InputStream slow = new InputStream() {
            int i = 0;

            @Override
            public int read() {
                return i < wire.length ? wire[i++] & 0xFF : -1;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (len == 0) return 0;
                int v = read();
                if (v < 0) return -1;
                b[off] = (byte) v;
                return 1;
            }
        };
        assertArrayEquals(frame, TcpFraming.read(new DataInputStream(slow)));
    }

    @Test
    void writeIssuesExactlyTwoWrites() throws IOException {
        var sizes = new java.util.ArrayList<Integer>();
        OutputStream counting = new OutputStream() {
            @Override
            public void write(int b) {
                fail("write(int) inesperado");
            }

            @Override
            public void write(byte[] b, int off, int len) {
                sizes.add(len);
            }
        };
        TcpFraming.write(counting, new byte[100]);
        assertEquals(java.util.List.of(4, 100), sizes);
    }

    @Test
    void readRejectsLengthAbove64KiB() {
        byte[] prefix = ByteBuffer.allocate(4).putInt(65537).array();
        var in = new DataInputStream(new ByteArrayInputStream(prefix));
        assertThrows(MessageTooLargeException.class, () -> TcpFraming.read(in));
    }

    @Test
    void readThrowsEofOnCleanEnd() {
        var in = new DataInputStream(new ByteArrayInputStream(new byte[0]));
        assertThrows(EOFException.class, () -> TcpFraming.read(in));
    }
}
