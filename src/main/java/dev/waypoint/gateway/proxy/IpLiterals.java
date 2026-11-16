package dev.waypoint.gateway.proxy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.regex.Pattern;

/** Parses IP literals only; never triggers a DNS lookup. */
public final class IpLiterals {

    private static final Pattern IPV4 = Pattern.compile(
            "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}");
    private static final Pattern IPV6_CHARS = Pattern.compile("[0-9a-fA-F:.]+");

    private IpLiterals() {}

    public static Optional<InetAddress> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String candidate = text.strip();
        if (candidate.startsWith("[") && candidate.endsWith("]")) {
            candidate = candidate.substring(1, candidate.length() - 1);
        }
        boolean v4 = IPV4.matcher(candidate).matches();
        boolean v6 = candidate.indexOf(':') >= 0 && IPV6_CHARS.matcher(candidate).matches();
        if (!v4 && !v6) {
            return Optional.empty();
        }
        try {
            return Optional.of(InetAddress.getByName(candidate));
        } catch (UnknownHostException e) {
            return Optional.empty();
        }
    }
}
