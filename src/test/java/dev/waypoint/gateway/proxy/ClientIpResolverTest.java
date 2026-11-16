package dev.waypoint.gateway.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClientIpResolverTest {

    private final ClientIpResolver resolver = new ClientIpResolver(List.of("10.0.0.0/8", "", "192.168.1.1"));

    private ClientIpResolver.ClientAddress resolve(String peer, String... xff) {
        return resolver.resolve(peer, Collections.enumeration(List.of(xff)));
    }

    @Test
    void untrustedPeerIgnoresForwardedFor() {
        ClientIpResolver.ClientAddress client = resolve("203.0.113.9", "1.1.1.1");
        assertThat(client.clientIp()).isEqualTo("203.0.113.9");
        assertThat(client.chain()).containsExactly("203.0.113.9");
        assertThat(client.viaTrustedProxy()).isFalse();
    }

    @Test
    void trustedPeerUsesRightMostUntrustedHopAndDropsSpoofedPrefix() {
        ClientIpResolver.ClientAddress client = resolve("10.0.0.9", "6.6.6.6, 198.51.100.7", "10.0.0.5");
        assertThat(client.clientIp()).isEqualTo("198.51.100.7");
        assertThat(client.chain()).containsExactly("198.51.100.7", "10.0.0.5", "10.0.0.9");
        assertThat(client.viaTrustedProxy()).isTrue();
    }

    @Test
    void garbageHopStopsTheWalk() {
        assertThat(resolve("10.0.0.9", "198.51.100.7, not-an-ip, 10.0.0.5").clientIp()).isEqualTo("10.0.0.5");
    }

    @Test
    void allTrustedChainUsesLeftMost() {
        assertThat(resolve("192.168.1.1", "10.1.1.1").clientIp()).isEqualTo("10.1.1.1");
    }

    @Test
    void trustedPeerWithoutForwardedFor() {
        assertThat(resolver.resolve("10.0.0.9", null).clientIp()).isEqualTo("10.0.0.9");
        assertThat(resolve("10.0.0.9", " ").clientIp()).isEqualTo("10.0.0.9");
    }

    @Test
    void unparseablePeerIsUsedVerbatim() {
        assertThat(resolve("unix-socket", "1.1.1.1").clientIp()).isEqualTo("unix-socket");
    }
}
