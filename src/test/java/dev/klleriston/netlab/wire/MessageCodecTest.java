package dev.klleriston.netlab.wire;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

class MessageCodecTest {

    @Test
    void roundTripPreservesAllFields() throws Exception {
        var m = new TestMessage(42L, 123_456_789L, new byte[] {1, 2, 3});
        var d = MessageCodec.decode(MessageCodec.encode(m), 0, 23);
        assertEquals(42L, d.seq());
        assertEquals(123_456_789L, d.sentAtNanos());
        assertArrayEquals(new byte[] {1, 2, 3}, d.payload());
    }

    @Test
    void encodedLengthIsHeaderPlusPayload() {
        assertEquals(20 + 64, MessageCodec.encode(TestMessage.of(1, 2, 64)).length);
    }

    @Test
    void decodeRejectsBufferShorterThanHeader() {
        assertThrows(MalformedMessageException.class, () -> MessageCodec.decode(new byte[10], 0, 10));
    }

    @Test
    void decodeRejectsPayloadLengthMismatch() {
        byte[] buf = new byte[30];
        ByteBuffer.wrap(buf).putLong(1).putLong(2).putInt(64);
        assertThrows(MalformedMessageException.class, () -> MessageCodec.decode(buf, 0, 30));
    }
}
