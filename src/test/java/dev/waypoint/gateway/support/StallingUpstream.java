package dev.waypoint.gateway.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;

/** Raw HTTP/1.1 upstream that sends a response head and some body bytes, then stalls or drops the connection. */
public final class StallingUpstream implements AutoCloseable {

    public enum Then { STALL, DROP }

    private record Script(String head, byte[] body, Then then) {}

    private final ServerSocket server;
    private final CountDownLatch released = new CountDownLatch(1);
    private volatile Script script = new Script("", new byte[0], Then.DROP);

    private StallingUpstream(ServerSocket server) {
        this.server = server;
        Thread.ofVirtual().name("stalling-upstream").start(this::acceptLoop);
    }

    public static StallingUpstream start() throws IOException {
        return new StallingUpstream(new ServerSocket(0, 50, InetAddress.getLoopbackAddress()));
    }

    public int port() {
        return server.getLocalPort();
    }

    /** A chunked 200 whose body is {@code bodyBytes} bytes in one chunk, never terminated. */
    public void respondChunked(int bodyBytes, Then then) {
        String head = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nTransfer-Encoding: chunked\r\nX-Upstream: stalling\r\n\r\n";
        byte[] chunk = bodyBytes == 0 ? new byte[0]
                : (Integer.toHexString(bodyBytes) + "\r\n" + "x".repeat(bodyBytes) + "\r\n").getBytes(StandardCharsets.US_ASCII);
        script = new Script(head, chunk, then);
    }

    @Override
    public void close() throws IOException {
        released.countDown();
        server.close();
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                Thread.ofVirtual().start(() -> serve(socket, script));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(Socket socket, Script current) {
        try (socket) {
            readRequestHead(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            out.write(current.head().getBytes(StandardCharsets.US_ASCII));
            out.write(current.body());
            out.flush();
            if (current.then() == Then.STALL) {
                released.await();
            }
        } catch (IOException e) {
            // The gateway closed its side, which is what a stall should eventually cause.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void readRequestHead(InputStream in) throws IOException {
        int matched = 0;
        byte[] terminator = {'\r', '\n', '\r', '\n'};
        for (int b; matched < terminator.length && (b = in.read()) != -1; ) {
            matched = b == terminator[matched] ? matched + 1 : (b == '\r' ? 1 : 0);
        }
    }
}
