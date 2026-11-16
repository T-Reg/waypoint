package dev.waypoint.gateway.path;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Produces the single canonical path used for the allowlist, route matching and forwarding.
 * Anything ambiguous is rejected rather than guessed at (design.md §5).
 */
public final class PathNormalizer {

    private PathNormalizer() {}

    public static String normalize(String rawPath) throws InvalidPathException {
        if (rawPath == null || rawPath.isEmpty() || rawPath.charAt(0) != '/') {
            throw new InvalidPathException("path must start with '/'");
        }
        String decoded = decodeOnce(rawPath);
        List<String> segments = new ArrayList<>();
        for (String segment : decoded.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                throw new InvalidPathException("'..' segments are not allowed");
            }
            segments.add(segment);
        }
        return "/" + String.join("/", segments);
    }

    /** Percent-encodes each segment of a canonical path per RFC 3986 {@code pchar}. */
    public static String encode(String canonicalPath) {
        StringBuilder out = new StringBuilder(canonicalPath.length() + 16);
        for (byte b : canonicalPath.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if (c == '/' || isPchar(c)) {
                out.append((char) c);
            } else {
                out.append('%').append(HEX[c >> 4]).append(HEX[c & 0xF]);
            }
        }
        return out.toString();
    }

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private static boolean isPchar(int c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || "-._~!$&'()*+,=:@".indexOf(c) >= 0;
    }

    private static String decodeOnce(String raw) throws InvalidPathException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '%') {
                if (i + 2 >= raw.length()) {
                    throw new InvalidPathException("truncated percent-encoding");
                }
                int hi = Character.digit(raw.charAt(i + 1), 16);
                int lo = Character.digit(raw.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) {
                    throw new InvalidPathException("invalid percent-encoding");
                }
                int value = (hi << 4) | lo;
                if (value == '/') {
                    throw new InvalidPathException("encoded '/' is not allowed");
                }
                rejectForbidden(value);
                bytes.write(value);
                i += 2;
            } else if (c > 0x7F) {
                int codePoint = raw.codePointAt(i);
                bytes.writeBytes(new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8));
                i += Character.charCount(codePoint) - 1;
            } else {
                rejectForbidden(c);
                bytes.write(c);
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray()))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new InvalidPathException("invalid UTF-8 in path");
        }
    }

    private static void rejectForbidden(int c) throws InvalidPathException {
        if (c == '%') {
            throw new InvalidPathException("double percent-encoding is not allowed");
        }
        if (c == '\\') {
            throw new InvalidPathException("backslash is not allowed");
        }
        if (c == ';') {
            throw new InvalidPathException("';' is not allowed");
        }
        if (c < 0x20 || c == 0x7F) {
            throw new InvalidPathException("control characters are not allowed");
        }
    }
}
