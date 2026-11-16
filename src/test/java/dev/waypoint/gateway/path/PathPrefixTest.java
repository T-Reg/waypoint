package dev.waypoint.gateway.path;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PathPrefixTest {

    @ParameterizedTest
    @CsvSource({
            "/api, /api, true",
            "/api, /api/x, true",
            "/api, /apix, false",
            "/api, /ap, false",
            "/api/v1, /api/v1/users, true",
            "/api/v1, /api/v10, false",
            "/, /anything, true",
            "/, /, true"
    })
    void matchesOnSegmentBoundaries(String prefix, String path, boolean expected) {
        assertThat(PathPrefix.matches(prefix, path)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"/weather, true", "/, true", "/weather/, false", "weather, false", "/a//b, false", "/a/../b, false", "/a%20b, false"})
    void validatesCanonicalPrefixes(String prefix, boolean valid) {
        assertThat(PathPrefix.isValid(prefix)).isEqualTo(valid);
    }

    @Test
    void nullPrefixIsInvalid() {
        assertThat(PathPrefix.isValid(null)).isFalse();
    }

    @Test
    void stripsPrefix() {
        assertThat(PathPrefix.strip("/weather", "/weather/today")).isEqualTo("/today");
        assertThat(PathPrefix.strip("/weather", "/weather")).isEqualTo("/");
        assertThat(PathPrefix.strip("/", "/weather")).isEqualTo("/weather");
    }
}
