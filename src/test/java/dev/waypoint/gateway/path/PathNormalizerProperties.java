package dev.waypoint.gateway.path;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class PathNormalizerProperties {

    private static final List<String> TRICKY = List.of("..", "..;", ".", "%2e%2e", "%252e", "%2F", "%2f", "\\", "%5C",
            ";", "%3B", "%", "%25", "//", "%00", "a", "b", "api", "x y", "%C3%A9", "~", "%7E");

    @Provide
    Arbitrary<String> rawPaths() {
        Arbitrary<String> segment = Arbitraries.oneOf(
                Arbitraries.of(TRICKY),
                Arbitraries.strings().withCharRange('!', '~').ofMinLength(0).ofMaxLength(6));
        return segment.list().ofMaxSize(6).map(parts -> "/" + String.join("/", parts));
    }

    @Property(tries = 2000)
    void normalizedOutputIsCanonicalAndIdempotent(@ForAll("rawPaths") String raw) {
        String normalized;
        try {
            normalized = PathNormalizer.normalize(raw);
        } catch (InvalidPathException e) {
            return;
        }
        assertThat(normalized).startsWith("/");
        assertThat(normalized).doesNotContain("//", ";", "%", "\\");
        assertThat(normalized.split("/")).doesNotContain("..", ".");
        try {
            assertThat(PathNormalizer.normalize(normalized)).isEqualTo(normalized);
        } catch (InvalidPathException e) {
            throw new AssertionError("normalized path was rejected on re-normalization: " + normalized, e);
        }
    }

    @Provide
    Arbitrary<String> canonicalPaths() {
        Arbitrary<String> segment = Arbitraries.strings().withCharRange('a', 'z').withChars('-', '.', '_', '~', ' ', 'é', '?', '@')
                .ofMinLength(1).ofMaxLength(5).filter(s -> !s.equals(".") && !s.equals(".."));
        return segment.list().ofMinSize(0).ofMaxSize(4).map(parts -> "/" + String.join("/", parts));
    }

    @Provide
    Arbitrary<List<Boolean>> encodingMasks() {
        return Arbitraries.of(true, false).list().ofSize(64);
    }

    /** Every percent-encoding of the same path (other than '/') yields the same canonical path and allowlist decision. */
    @Property(tries = 1000)
    void allEncodingsAgree(@ForAll("canonicalPaths") String canonical, @ForAll("encodingMasks") List<Boolean> mask) throws InvalidPathException {
        String variant = encodeSome(canonical, mask);
        String normalized = PathNormalizer.normalize(variant);
        assertThat(normalized).isEqualTo(canonical);
        assertThat(PathPrefix.matches("/a", normalized)).isEqualTo(PathPrefix.matches("/a", canonical));
    }

    private static String encodeSome(String canonical, List<Boolean> mask) {
        StringBuilder out = new StringBuilder();
        byte[] bytes = canonical.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xFF;
            boolean mustEncode = b > 0x7F || b == ' ' || b == '?';
            if (b != '/' && (mustEncode || mask.get(i % mask.size()))) {
                out.append('%').append(String.format("%02X", b));
            } else {
                out.append((char) b);
            }
        }
        return out.toString();
    }

    @Property(tries = 500)
    void dotDotNeverSurvivesAnyEncoding(@ForAll("encodingMasks") List<Boolean> mask) {
        String variant = "/a/" + encodeSome("..", mask) + "/b";
        try {
            String normalized = PathNormalizer.normalize(variant);
            assertThat(normalized.split("/")).doesNotContain("..");
        } catch (InvalidPathException expected) {
            // rejection is the expected outcome
        }
    }

    @Property(tries = 200)
    void doubleEncodingIsAlwaysRejected(@ForAll("canonicalPaths") String canonical) {
        String once = PathNormalizer.encode(canonical);
        String twice = once.replace("%", "%25");
        if (twice.equals(once)) {
            return;
        }
        try {
            PathNormalizer.normalize(twice);
            throw new AssertionError("double-encoded path accepted: " + twice);
        } catch (InvalidPathException expected) {
            // rejected
        }
    }
}
