package dev.earclean.preview;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class BatteryReading {
    private static final int MAGIC = 0x9999;
    private static final int COMMAND = 0x1017;
    final int percent;
    final boolean charging;
    final boolean full;

    private BatteryReading(int percent, boolean charging, boolean full) {
        this.percent = percent;
        this.charging = charging;
        this.full = full;
    }

    static byte[] request(int sequence) {
        return ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) MAGIC).putShort((short) COMMAND).putInt(sequence).array();
    }

    static BatteryReading parse(byte[] reply, int length) {
        if (length < 20 || length > reply.length) return null;
        ByteBuffer data = ByteBuffer.wrap(reply).order(ByteOrder.LITTLE_ENDIAN);
        if ((data.getShort(0) & 0xffff) != MAGIC || (data.getShort(2) & 0xffff) != COMMAND) return null;
        int extended = data.getInt(16);
        int percent = extended & 0xffff;
        if ((extended & 0x10000) == 0 || percent > 100) return null;
        int flags = data.getInt(8) >>> 16;
        return new BatteryReading(percent, (flags & 1) != 0, (flags & 2) != 0);
    }

    int estimatedMinutes() {
        // Local heuristic. The device reports percent, not an estimated runtime.
        return percent < 20 ? percent * 15 / 20 : 15 + (percent - 20) * 7 / 16;
    }
}
