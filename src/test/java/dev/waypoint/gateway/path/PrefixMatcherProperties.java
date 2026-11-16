package dev.waypoint.gateway.path;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class PrefixMatcherProperties {

    @Provide
    Arbitrary<String> paths() {
        return Arbitraries.strings().withChars('a', 'b', 'x').ofMinLength(1).ofMaxLength(3)
                .list().ofMaxSize(4).map(parts -> "/" + String.join("/", parts));
    }

    /** Reference: a prefix matches when its segments are a leading run of the path's segments. */
    static boolean reference(String prefix, String path) {
        List<String> p = segments(prefix);
        List<String> s = segments(path);
        return p.size() <= s.size() && s.subList(0, p.size()).equals(p);
    }

    private static List<String> segments(String path) {
        return Arrays.stream(path.split("/")).filter(seg -> !seg.isEmpty()).toList();
    }

    @Property(tries = 3000)
    void agreesWithReference(@ForAll("paths") String prefix, @ForAll("paths") String path) {
        assertThat(PathPrefix.matches(prefix, path)).isEqualTo(reference(prefix, path));
    }
}
