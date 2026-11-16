package dev.waypoint.gateway.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IpLiteralsAndCidrTest {

    @ParameterizedTest
    @ValueSource(strings = {"1.2.3.4", " 10.0.0.1 ", "::1", "[::1]", "2001:db8::1", "::ffff:1.2.3.4"})
    void parsesLiterals(String text) {
        assertThat(IpLiterals.parse(text)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "example.com", "1.2.3", "256.1.1.1", "01.2.3.4", "evil.com:80", ":::", "1.2.3.4.5"})
    void neverResolvesNames(String text) {
        assertThat(IpLiterals.parse(text)).isEmpty();
    }

    @Test
    void nullIsEmpty() {
        assertThat(IpLiterals.parse(null)).isEmpty();
    }

    @Test
    void cidrContainment() {
        CidrBlock block = CidrBlock.parse("172.16.0.0/12");
        assertThat(block.contains(IpLiterals.parse("172.31.255.255").orElseThrow())).isTrue();
        assertThat(block.contains(IpLiterals.parse("172.32.0.0").orElseThrow())).isFalse();
        assertThat(block.contains(IpLiterals.parse("::1").orElseThrow())).isFalse();
        assertThat(CidrBlock.parse("10.0.0.1").contains(IpLiterals.parse("10.0.0.1").orElseThrow())).isTrue();
        assertThat(CidrBlock.parse("0.0.0.0/0").contains(IpLiterals.parse("8.8.8.8").orElseThrow())).isTrue();
        assertThat(CidrBlock.parse("fe80::/10").contains(IpLiterals.parse("febf::1").orElseThrow())).isTrue();
        assertThat(CidrBlock.parse("fe80::/10").contains(IpLiterals.parse("fec0::1").orElseThrow())).isFalse();
    }

    @Test
    void rejectsInvalidCidrs() {
        assertThatThrownBy(() -> CidrBlock.parse("example.com/8")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CidrBlock.parse("10.0.0.0/33")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CidrBlock.parse("10.0.0.0/-1")).isInstanceOf(IllegalArgumentException.class);
    }
}
