package dev.waypoint.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.waypoint.gateway.listener.ListenerPorts;
import dev.waypoint.gateway.proxy.IpLiterals;
import java.net.InetAddress;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UpstreamAddressPolicyTest {

    private final UpstreamAddressPolicy policy = new UpstreamAddressPolicy(new ListenerPorts() {
        @Override
        public boolean isOwnListenerPort(int port) {
            return port == 8081;
        }
    });

    @ParameterizedTest
    @ValueSource(strings = {"ftp://example.com", "example.com/x", "/relative", "http://user:pw@example.com",
            "http://example.com/x?y=1", "http://example.com/#frag", "http://", "mailto:a@b.c", "http://exa mple.com"})
    void rejectsBadSyntax(String url) {
        assertThat(policy.checkSyntax(url, true)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://169.254.169.254/latest/meta-data", "http://[fe80::1]/", "http://0.0.0.0/", "http://224.0.0.1/",
            "http://[::]/", "http://[ff02::1]/", "http://240.0.0.1/"})
    void alwaysDeniesLinkLocalMetadataAndUnroutable(String url) {
        assertThat(policy.checkSyntax(url, true)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1:8080", "http://10.1.2.3", "http://172.16.0.1", "http://192.168.1.1",
            "http://100.64.0.1", "http://[::1]:80", "http://[fd00::1]", "http://[::ffff:127.0.0.1]"})
    void privateDeniedUnlessOptedIn(String url) {
        assertThat(policy.checkSyntax(url, false)).isPresent();
        assertThat(policy.checkSyntax(url, true)).isEmpty();
    }

    @Test
    void ownListenersDeniedEvenWithOptIn() {
        assertThat(policy.checkSyntax("http://127.0.0.1:8081", true)).get().asString().contains("own listeners");
        assertThat(policy.checkSyntax("http://127.0.0.1:8082", true)).isEmpty();
        assertThat(policy.checkSyntax("http://[::1]:8081", true)).isPresent();
    }

    @Test
    void anotherHostOnTheSamePortIsNotSelf() {
        assertThat(policy.checkSyntax("http://10.255.255.1:8081", true)).as("a different container on port 8081").isEmpty();
        assertThat(policy.checkSyntax("http://10.255.255.1:8081", false)).get().asString().contains("private");
    }

    @Test
    void localInterfaceAddressesCountAsThisHost() throws Exception {
        assertThat(UpstreamAddressPolicy.isThisHost(InetAddress.getLoopbackAddress())).isTrue();
        assertThat(UpstreamAddressPolicy.isThisHost(IpLiterals.parse("0.0.0.0").orElseThrow())).isTrue();
        assertThat(UpstreamAddressPolicy.isThisHost(IpLiterals.parse("10.255.255.1").orElseThrow())).isFalse();
        java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces()).stream()
                .flatMap(i -> java.util.Collections.list(i.getInetAddresses()).stream())
                .findFirst()
                .ifPresent(local -> assertThat(UpstreamAddressPolicy.isThisHost(local)).isTrue());
    }

    @Test
    void publicAddressesAllowed() {
        assertThat(policy.checkSyntax("https://93.184.216.34/api", false)).isEmpty();
        assertThat(policy.checkSyntax("https://api.example.com/base/", false)).isEmpty();
    }

    @Test
    void resolvedCheckCatchesHostnamesThatPointInside() {
        assertThat(policy.checkResolved(URI.create("http://localhost:1234/"), false)).isPresent();
        assertThat(policy.checkResolved(URI.create("http://localhost:1234/"), true)).isEmpty();
        assertThat(policy.checkResolved(URI.create("http://does-not-exist.invalid/"), true)).get().asString().contains("does not resolve");
    }

    @Test
    void defaultPortsFollowScheme() {
        assertThat(UpstreamAddressPolicy.port(URI.create("https://x"))).isEqualTo(443);
        assertThat(UpstreamAddressPolicy.port(URI.create("http://x"))).isEqualTo(80);
        assertThat(UpstreamAddressPolicy.port(URI.create("http://x:9"))).isEqualTo(9);
    }

    @Test
    void denialReasonForPublicAddressIsEmpty() {
        InetAddress address = IpLiterals.parse("8.8.8.8").orElseThrow();
        assertThat(policy.denialReason(address, 443, false)).isEmpty();
    }
}
