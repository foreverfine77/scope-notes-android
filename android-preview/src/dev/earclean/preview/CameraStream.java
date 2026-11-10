package dev.earclean.preview;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.PortUnreachableException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class CameraStream {
    private static final String CAMERA_IP = "192.168.0.123";
    private static final int VIDEO_PORT = 8031;
    private static final int CONTROL_PORT = 50000;
    private static final int BOARD_INFO = 0x1060;
    private static final byte[] GET_KEY = {
            'S', 'E', 'T', 'C', 'M', 'D', (byte) 0xd3, 4, 0, 0, (byte) 0x90, 0, 4, 0, 0, 0, 0, 0
    };
    private static final byte[] START = videoCommand(1);
    private static final byte[] STOP = videoCommand(2);

    interface Listener {
        void status(CameraStream source, int statusId);
        void connected(CameraStream source, Network network);
        void frame(CameraStream source, Bitmap image, float rotation);
        void detail(CameraStream source, String message);
        void battery(CameraStream source, BatteryReading reading);
    }

    private final ConnectivityManager connectivity;
    private final EarWifi managedWifi;
    private final Listener listener;
    private final Thread worker;
    private volatile boolean stopped;
    private volatile boolean stabilize;

    CameraStream(ConnectivityManager connectivity, EarWifi managedWifi, Listener listener) {
        this.connectivity = connectivity;
        this.managedWifi = managedWifi;
        this.listener = listener;
        worker = new Thread(this::run, "earclean-stream");
    }

    void start() {
        worker.start();
    }

    void setStabilize(boolean enabled) {
        stabilize = enabled;
    }

    void stop() {
        stopped = true;
        worker.interrupt();
    }

    private void run() {
        while (!stopped) {
            Network network = findCameraNetwork();
            if (network == null) {
                listener.status(this, managedWifi == null ? R.string.wifi_waiting : R.string.wifi_reconnecting);
            } else {
                listener.status(this, R.string.connecting);
                try {
                    receive(network);
                } catch (IOException | SecurityException error) {
                    if (!stopped) {
                        listener.detail(this, "断流: " + error.getClass().getSimpleName()
                                + " " + error.getMessage());
                        listener.status(this, R.string.stream_reconnecting);
                    }
                }
            }
            if (stopped) return;
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {
                return;
            }
        }
    }

    private Network findCameraNetwork() {
        if (managedWifi != null) return managedWifi.network();
        for (Network network : connectivity.getAllNetworks()) {
            NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
            LinkProperties link = connectivity.getLinkProperties(network);
            if (capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || link == null) continue;
            for (LinkAddress address : link.getLinkAddresses()) {
                String ip = address.getAddress().getHostAddress();
                if (ip != null && ip.startsWith("192.168.0.")) return network;
            }
        }
        return null;
    }

    private void receive(Network network) throws IOException {
        InetAddress address = InetAddress.getByName(CAMERA_IP);
        try (DatagramSocket control = new DatagramSocket(); DatagramSocket video = new DatagramSocket()) {
            network.bindSocket(control);
            network.bindSocket(video);
            control.connect(address, CONTROL_PORT);
            control.setSoTimeout(700);
            send(control, GET_KEY);
            try {
                DatagramPacket reply = new DatagramPacket(new byte[128], 128);
                control.receive(reply);
                listener.detail(this, "控制 UDP 50000 回包: " + reply.getLength() + " B");
            } catch (SocketTimeoutException | PortUnreachableException ignored) {
                listener.detail(this, "控制 UDP 50000 未回包，继续尝试取流");
            }
            if (stopped) return;

            video.connect(address, VIDEO_PORT);
            video.setReceiveBufferSize(256 * 1024);
            video.setSoTimeout(220);
            JpegFrames assembler = new JpegFrames();
            RollFilter roll = new RollFilter();
            BackgroundRoll background = new BackgroundRoll();
            RollThumbnail thumbnail = new RollThumbnail();
            byte[] bytes = new byte[8192];
            DatagramPacket packet = new DatagramPacket(bytes, bytes.length);
            long lastFrame = SystemClock.elapsedRealtime();
            long nextStart = lastFrame;
            StreamStats stats = new StreamStats(lastFrame);
            listener.detail(this, "开始收流 UDP 8031");
            Thread battery = new Thread(() -> pollBattery(network, address), "earclean-battery");
            battery.start();
            try {
                while (!stopped) {
                    if (managedWifi != null && !network.equals(managedWifi.network())) {
                        throw new IOException("耳镜 Wi-Fi 已断开");
                    }
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastFrame > 3000) {
                        throw new SocketTimeoutException("3000 ms 内无可解码画面");
                    }
                    if (now - stats.started >= 1000) {
                        String report = stats.sample(now) + assembler.diagnostics();
                        Log.d("EarcleanStream", report);
                        listener.detail(this, report);
                    }
                    if (now >= nextStart) {
                        send(video, START);
                        stats.starts++;
                        nextStart = now + 800;
                    }
                    packet.setLength(bytes.length);
                    try {
                        video.receive(packet);
                    } catch (SocketTimeoutException ignored) {
                        continue;
                    }
                    stats.packets++;
                    JpegFrames.Frame frame = assembler.accept(bytes, packet.getLength());
                    if (frame == null || stopped) continue;
                    Bitmap image = BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.length);
                    if (image == null) {
                        stats.decodeErrors++;
                        continue;
                    }
                    if (!stats.receivedFirst) {
                        stats.receivedFirst = true;
                        listener.connected(this, network);
                        listener.detail(this, "首帧 " + image.getWidth() + "x" + image.getHeight()
                                + " " + frame.jpeg.length + " B");
                    }
                    long decodedAt = SystemClock.elapsedRealtime();
                    stats.longestGap = Math.max(stats.longestGap, decodedAt - lastFrame);
                    lastFrame = decodedAt;
                    float gravity = roll.update(frame.acceleration, lastFrame);
                    float backgroundDelta = Float.NaN;
                    boolean stabilizationEnabled = stabilize;
                    if (stabilizationEnabled) {
                        backgroundDelta = background.update(thumbnail.luma(image));
                    } else {
                        background.reset();
                    }
                    float rotation = roll.correction(gravity, backgroundDelta);
                    stats.frames++;
                    stats.acceleration = frame.acceleration;
                    stats.rotation = gravity;
                    stats.background = backgroundDelta;
                    if (stabilizationEnabled) stats.countStabilization(roll.source());
                    listener.frame(this, image, rotation);
                }
            } finally {
                battery.interrupt();
                try {
                    send(video, STOP);
                } catch (IOException ignored) {
                    // The Wi-Fi network may already have gone away.
                }
            }
        }
    }

    private static final class RollThumbnail {
        private static final int SIZE = BackgroundRoll.SIZE;
        private final Bitmap bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        private final Canvas canvas = new Canvas(bitmap);
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Rect source = new Rect();
        private final Rect destination = new Rect(0, 0, SIZE, SIZE);
        private final int[] pixels = new int[SIZE * SIZE];

        int[] luma(Bitmap frame) {
            int size = Math.min(frame.getWidth(), frame.getHeight());
            int left = (frame.getWidth() - size) / 2;
            int top = (frame.getHeight() - size) / 2;
            source.set(left, top, left + size, top + size);
            canvas.drawBitmap(frame, source, destination, paint);
            bitmap.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE);
            for (int i = 0; i < pixels.length; i++) {
                int rgb = pixels[i];
                pixels[i] = (((rgb >> 16) & 255) * 77 + ((rgb >> 8) & 255) * 150
                        + (rgb & 255) * 29) >> 8;
            }
            return pixels;
        }
    }

    private void pollBattery(Network network, InetAddress address) {
        readBoardInfo(network, address);
        int sequence = 1236;
        while (!stopped && !Thread.currentThread().isInterrupted()) {
            BatteryReading reading = null;
            try (DatagramSocket socket = new DatagramSocket()) {
                network.bindSocket(socket);
                socket.connect(address, CONTROL_PORT);
                socket.setSoTimeout(700);
                send(socket, BatteryReading.request(sequence++));
                byte[] bytes = new byte[64];
                DatagramPacket reply = new DatagramPacket(bytes, bytes.length);
                socket.receive(reply);
                reading = BatteryReading.parse(bytes, reply.getLength());
                String result = reading == null ? "未识别百分比" : reading.percent + "%";
                if (reply.getLength() >= 20) {
                    ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                    result += String.format(Locale.ROOT, " 原始=%08x/%08x", data.getInt(8), data.getInt(16));
                }
                String report = "电量回包 " + reply.getLength() + " B：" + result;
                Log.d("EarcleanBattery", report);
                listener.detail(this, report);
            } catch (IOException | SecurityException ignored) {
                Log.d("EarcleanBattery", "电量查询未回包");
                listener.detail(this, "电量查询未回包");
            }
            if (stopped || Thread.currentThread().isInterrupted()) return;
            listener.battery(this, reading);
            try {
                Thread.sleep(15000);
            } catch (InterruptedException ignored) {
                return;
            }
        }
    }

    private void readBoardInfo(Network network, InetAddress address) {
        try (DatagramSocket socket = new DatagramSocket()) {
            network.bindSocket(socket);
            socket.connect(address, CONTROL_PORT);
            socket.setSoTimeout(600);
            byte[] request = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                    .putShort((short) 0x9999).putShort((short) BOARD_INFO).putInt(1235).array();
            send(socket, request);
            byte[] bytes = new byte[1400];
            DatagramPacket reply = new DatagramPacket(bytes, bytes.length);
            socket.receive(reply);
            int headerSize = 24;
            if (reply.getLength() < headerSize) return;
            ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            if ((header.getShort(0) & 0xffff) != 0x9999
                    || (header.getShort(2) & 0xffff) != BOARD_INFO) return;
            int length = header.getInt(12);
            if (length <= 0 || length > reply.getLength() - headerSize) return;
            int checksum = 0;
            for (int i = headerSize; i < headerSize + length; i++) checksum ^= bytes[i] & 0xff;
            if (checksum != header.getInt(8)) return;
            String json = new String(bytes, headerSize, length, StandardCharsets.UTF_8).trim();
            String model = new JSONObject(json).optString("ssid_head");
            if (!model.isEmpty()) listener.detail(this, "耳镜型号 " + model);
        } catch (IOException | SecurityException | JSONException ignored) {
            // Older firmwares can stream without implementing the board-info command.
        }
    }

    private static final class StreamStats {
        long started;
        int packets;
        int frames;
        int decodeErrors;
        int starts;
        int acceleration;
        float rotation = Float.NaN;
        float background = Float.NaN;
        int backgroundFrames;
        int gravityFrames;
        int heldFrames;
        long longestGap;
        boolean receivedFirst;

        StreamStats(long now) {
            started = now;
        }

        void countStabilization(RollFilter.Source source) {
            switch (source) {
                case BACKGROUND: backgroundFrames++; break;
                case GRAVITY: gravityFrames++; break;
                case HOLD: heldFrames++; break;
            }
        }

        String sample(long now) {
            String line = String.format(Locale.ROOT,
                    "包=%d 帧=%d FPS=%.1f 解码失败=%d 续流=%d 帧间最大=%dms"
                            + " 姿态=%08x 姿态角=%s 背景变化=%s 稳向帧[背景=%d 姿态=%d 保持=%d]",
                    packets, frames, frames * 1000.0 / (now - started),
                    decodeErrors, starts, longestGap, acceleration, angle(rotation), angle(background),
                    backgroundFrames, gravityFrames, heldFrames);
            started = now;
            packets = frames = decodeErrors = starts = 0;
            backgroundFrames = gravityFrames = heldFrames = 0;
            longestGap = 0;
            return line;
        }

        private static String angle(float degrees) {
            return Float.isNaN(degrees) ? "—" : String.format(Locale.ROOT, "%.1f°", degrees);
        }
    }

    private static byte[] videoCommand(int command) {
        byte[] bytes = new byte[24];
        bytes[0] = bytes[1] = (byte) 0x99;
        bytes[2] = (byte) command;
        return bytes;
    }

    private static void send(DatagramSocket socket, byte[] bytes) throws IOException {
        socket.send(new DatagramPacket(bytes, bytes.length));
    }
}
