package dev.waypoint.gateway.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Sends a literal HTTP/1.1 request, for headers and paths the JDK client refuses to send. */
public final class RawHttp {

    private RawHttp() {}

    public record Response(int status, String head, String body) {}

    public static Response send(int port, String requestHead) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(requestHead.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, n);
            }
            String text = buffer.toString(StandardCharsets.ISO_8859_1);
            int split = text.indexOf("\r\n\r\n");
            String head = split >= 0 ? text.substring(0, split) : text;
            String body = split >= 0 ? text.substring(split + 4) : "";
            int status = Integer.parseInt(head.substring(9, 12));
            return new Response(status, head, body);
        }
    }

    /** A GET with {@code Connection: close} so the read terminates. */
    public static Response get(int port, String rawTarget, String... headerLines) throws IOException {
        StringBuilder request = new StringBuilder("GET ").append(rawTarget).append(" HTTP/1.1\r\nHost: localhost\r\n");
        for (String line : headerLines) {
            request.append(line).append("\r\n");
        }
        request.append("Connection: close\r\n\r\n");
        return send(port, request.toString());
    }
}
