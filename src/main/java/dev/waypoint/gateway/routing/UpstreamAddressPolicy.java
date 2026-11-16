package dev.waypoint.gateway.routing;

import dev.waypoint.gateway.listener.ListenerPorts;
import dev.waypoint.gateway.proxy.CidrBlock;
import dev.waypoint.gateway.proxy.IpLiterals;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Optional;

/**
 * SSRF guard for upstream URLs (OWASP SSRF Prevention Cheat Sheet). Link-local (cloud metadata),
 * wildcard, multicast and the gateway's own listeners are always denied; loopback and private
 * ranges are denied unless the route opts in with {@code allowPrivateNetwork}.
 */
public class UpstreamAddressPolicy {

    private static final List<CidrBlock> ALWAYS_DENIED = List.of(
            CidrBlock.parse("0.0.0.0/8"),
            CidrBlock.parse("169.254.0.0/16"),
            CidrBlock.parse("224.0.0.0/4"),
            CidrBlock.parse("240.0.0.0/4"),
            CidrBlock.parse("::/128"),
            CidrBlock.parse("fe80::/10"),
            CidrBlock.parse("ff00::/8"));

    private static final List<CidrBlock> PRIVATE = List.of(
            CidrBlock.parse("127.0.0.0/8"),
            CidrBlock.parse("10.0.0.0/8"),
            CidrBlock.parse("172.16.0.0/12"),
            CidrBlock.parse("192.168.0.0/16"),
            CidrBlock.parse("100.64.0.0/10"),
            CidrBlock.parse("::1/128"),
            CidrBlock.parse("fc00::/7"));

    private final ListenerPorts listenerPorts;

    public UpstreamAddressPolicy(ListenerPorts listenerPorts) {
        this.listenerPorts = listenerPorts;
    }

    /** Syntax checks only; safe to run without DNS (used when loading from the DB). */
    public Optional<String> checkSyntax(String url, boolean allowPrivateNetwork) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (Exception e) {
            return Optional.of("not a valid URL: " + url);
        }
        if (!uri.isAbsolute() || uri.isOpaque()) {
            return Optional.of("upstream URL must be absolute: " + url);
        }
        String scheme = uri.getScheme().toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return Optional.of("upstream URL scheme must be http or https: " + url);
        }
        if (uri.getRawUserInfo() != null) {
            return Optional.of("upstream URL must not contain userinfo: " + url);
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            return Optional.of("upstream URL must have a host: " + url);
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            return Optional.of("upstream URL must not contain a query or fragment: " + url);
        }
        Optional<InetAddress> literal = IpLiterals.parse(uri.getHost());
        if (literal.isPresent()) {
            return denialReason(literal.get(), port(uri), allowPrivateNetwork);
        }
        return Optional.empty();
    }

    /** Resolves the host and checks every address; runs on admin writes and before each upstream call. */
    public Optional<String> checkResolved(URI uri, boolean allowPrivateNetwork) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(uri.getHost());
        } catch (UnknownHostException e) {
            return Optional.of("upstream host does not resolve: " + uri.getHost());
        }
        for (InetAddress address : addresses) {
            Optional<String> reason = denialReason(address, port(uri), allowPrivateNetwork);
            if (reason.isPresent()) {
                return reason;
            }
        }
        return Optional.empty();
    }

    Optional<String> denialReason(InetAddress address, int port, boolean allowPrivateNetwork) {
        if (ALWAYS_DENIED.stream().anyMatch(block -> block.contains(address))) {
            return Optional.of("upstream address is not routable or is link-local: " + address.getHostAddress());
        }
        if (listenerPorts.isOwnListenerPort(port) && isThisHost(address)) {
            return Optional.of("upstream must not be one of the gateway's own listeners");
        }
        boolean isPrivate = PRIVATE.stream().anyMatch(block -> block.contains(address));
        if (isPrivate && !allowPrivateNetwork) {
            return Optional.of("upstream address is private or loopback: " + address.getHostAddress());
        }
        return Optional.empty();
    }

    /** Loopback, wildcard, or an address bound to one of this host's interfaces; fails closed on error. */
    static boolean isThisHost(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress()) {
            return true;
        }
        try {
            return NetworkInterface.getByInetAddress(address) != null;
        } catch (SocketException e) {
            return true;
        }
    }

    static int port(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
