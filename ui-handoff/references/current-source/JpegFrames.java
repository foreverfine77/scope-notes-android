package dev.earclean.preview;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;

final class JpegFrames {
    private static final int HEADER_SIZE = 24;
    private static final int MAX_FRAME_BYTES = 4 * 1024 * 1024;
    private static final int MAX_CHUNKS = 4096;
    private final ArrayList<byte[]> chunks = new ArrayList<>();
    private int frameSize;
    private int firstChunk;
    private int received;
    private int nextChunk;
    private int acceleration;
    private int incomplete;
    private int invalid;
    private int missingEnd;
    private int trimmed;
    private int duplicates;
    private int reordered;

    Frame accept(byte[] packet, int length) {
        if (length < HEADER_SIZE || packet[0] != 0x66 || packet[2] != 1) {
            invalid++;
            return null;
        }
        ByteBuffer header = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
        int size = header.getInt(4);
        int chunk = header.getShort(12) & 0xffff;
        int count = header.getShort(14) & 0xffff;
        if (count == 0 || count > length - HEADER_SIZE) {
            invalid++;
            return null;
        }

        if (count >= 3 && packet[24] == (byte) 0xff && packet[25] == (byte) 0xd8
                && packet[26] == (byte) 0xff) {
            if (frameSize != 0) incomplete++;
            chunks.clear();
            received = 0;
            frameSize = size > 0 && size <= MAX_FRAME_BYTES ? size : 0;
            firstChunk = chunk;
            nextChunk = 0;
            acceleration = header.getInt(16);
        }
        if (frameSize == 0) return null;
        int index = chunk - firstChunk;
        if (size != frameSize || index < 0 || index >= MAX_CHUNKS) {
            invalid++;
            return null;
        }
        while (chunks.size() <= index) chunks.add(null);
        if (chunks.get(index) != null) {
            duplicates++;
            return null;
        }
        if (count > frameSize - received) {
            incomplete++;
            frameSize = 0;
            chunks.clear();
            return null;
        }
        if (index != nextChunk) reordered++;
        chunks.set(index, Arrays.copyOfRange(packet, HEADER_SIZE, HEADER_SIZE + count));
        received += count;
        while (nextChunk < chunks.size() && chunks.get(nextChunk) != null) nextChunk++;
        if (received < frameSize || nextChunk != chunks.size()) return null;

        byte[] jpeg = new byte[frameSize];
        int position = 0;
        for (byte[] part : chunks) {
            System.arraycopy(part, 0, jpeg, position, part.length);
            position += part.length;
        }
        chunks.clear();
        frameSize = 0;
        // Some firmwares include bytes after EOI in the declared frame length.
        // Keep only the JPEG, and never manufacture a missing end marker.
        int scan = scanOffset(jpeg);
        for (int i = jpeg.length - 2; scan >= 0 && i >= scan; i--) {
            if (jpeg[i] == (byte) 0xff && jpeg[i + 1] == (byte) 0xd9) {
                if (i + 2 != jpeg.length) {
                    trimmed++;
                    jpeg = Arrays.copyOf(jpeg, i + 2);
                }
                return new Frame(jpeg, acceleration);
            }
        }
        missingEnd++;
        return null;
    }

    private static int scanOffset(byte[] jpeg) {
        int offset = 2;
        while (offset < jpeg.length && jpeg[offset] == (byte) 0xff) {
            while (offset < jpeg.length && jpeg[offset] == (byte) 0xff) offset++;
            if (offset + 2 >= jpeg.length) return -1;
            int marker = jpeg[offset++] & 0xff;
            int length = ((jpeg[offset] & 0xff) << 8) | (jpeg[offset + 1] & 0xff);
            if (length < 2 || length > jpeg.length - offset) return -1;
            offset += length;
            if (marker == 0xda) return offset;
        }
        return -1;
    }

    String diagnostics() {
        String result = " 未拼齐=" + incomplete + " 格式异常=" + invalid
                + " JPEG缺尾=" + missingEnd + " 裁除尾填充=" + trimmed
                + " 重包=" + duplicates + " 乱序=" + reordered;
        incomplete = invalid = missingEnd = trimmed = duplicates = reordered = 0;
        return result;
    }

    static final class Frame {
        final byte[] jpeg;
        final int acceleration;

        Frame(byte[] jpeg, int acceleration) {
            this.jpeg = jpeg;
            this.acceleration = acceleration;
        }
    }
}
