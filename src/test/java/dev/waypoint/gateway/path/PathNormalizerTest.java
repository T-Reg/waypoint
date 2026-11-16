package dev.waypoint.gateway.path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PathNormalizerTest {

    @ParameterizedTest
    @CsvSource({
            "/, /",
            "/weather, /weather",
            "/weather/, /weather",
            "//weather//today, /weather/today",
            "/weather/./today, /weather/today",
            "/./, /",
            "/caf%C3%A9, /café",
            "/a%20b, /a b",
            "/a%3Fb, /a?b",
            "/Weather, /Weather",
            "/café, /café"
    })
    void normalizes(String raw, String expected) throws InvalidPathException {
        assertThat(PathNormalizer.normalize(raw)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/a/../b", "/..", "/a/%2e%2e/b", "/a/%2E%2E", "/..;/admin", "/a;b", "/a%3bb",
            "/a%2Fb", "/a%2fb", "/a%5Cb", "/a\\b", "/a%252e%252e", "/a%25", "/a%", "/a%2", "/a%zz",
            "/a%00b", "/a%0Ab", "/a%7F", "/a%C3", "relative", ""
    })
    void rejectsAmbiguousPaths(String raw) {
        assertThatThrownBy(() -> PathNormalizer.normalize(raw)).isInstanceOf(InvalidPathException.class);
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> PathNormalizer.normalize(null)).isInstanceOf(InvalidPathException.class);
    }

    @Test
    void rejectsRawControlCharacters() {
        assertThatThrownBy(() -> PathNormalizer.normalize("/a\u0001b")).isInstanceOf(InvalidPathException.class);
    }

    @Test
    void encodesNonPcharBytesPerRfc3986() {
        assertThat(PathNormalizer.encode("/a b/café/x?y#z")).isEqualTo("/a%20b/caf%C3%A9/x%3Fy%23z");
        assertThat(PathNormalizer.encode("/keep-._~!$&'()*+,=:@")).isEqualTo("/keep-._~!$&'()*+,=:@");
    }

    @Test
    void encodedFormNormalizesBackToCanonical() throws InvalidPathException {
        String canonical = "/a b/café/x?y";
        assertThat(PathNormalizer.normalize(PathNormalizer.encode(canonical))).isEqualTo(canonical);
    }
}
