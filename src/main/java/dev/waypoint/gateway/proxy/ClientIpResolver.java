package dev.waypoint.gateway.proxy;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;

/**
 * Client IP from {@code X-Forwarded-For} only when the socket peer is a trusted proxy: the
 * right-most address that isn't itself trusted. Otherwise the socket peer, so clients can't spoof it.
 */
public class ClientIpResolver {

    public record ClientAddress(String clientIp, List<String> chain, boolean viaTrustedProxy) {}

    private final List<CidrBlock> trustedProxies;

    public ClientIpResolver(List<String> trustedProxyCidrs) {
        this.trustedProxies = trustedProxyCidrs.stream().filter(c -> c != null && !c.isBlank()).map(CidrBlock::parse).toList();
    }

    public ClientAddress resolve(String remoteAddr, Enumeration<String> forwardedFor) {
        Optional<InetAddress> peer = IpLiterals.parse(remoteAddr);
        if (peer.isEmpty() || !isTrusted(peer.get())) {
            return new ClientAddress(remoteAddr, List.of(remoteAddr), false);
        }
        List<String> hops = new ArrayList<>();
        if (forwardedFor != null) {
            for (String header : Collections.list(forwardedFor)) {
                for (String part : header.split(",")) {
                    if (!part.isBlank()) {
                        hops.add(part.strip());
                    }
                }
            }
        }
        hops.add(remoteAddr);
        int clientIndex = hops.size() - 1;
        for (int i = hops.size() - 2; i >= 0; i--) {
            Optional<InetAddress> hop = IpLiterals.parse(hops.get(i));
            if (hop.isEmpty()) {
                break;
            }
            clientIndex = i;
            if (!isTrusted(hop.get())) {
                break;
            }
        }
        List<String> chain = hops.subList(clientIndex, hops.size()).stream()
                .map(h -> IpLiterals.parse(h).map(InetAddress::getHostAddress).orElse(h)).toList();
        return new ClientAddress(chain.get(0), chain, true);
    }

    private boolean isTrusted(InetAddress address) {
        return trustedProxies.stream().anyMatch(block -> block.contains(address));
    }
}
