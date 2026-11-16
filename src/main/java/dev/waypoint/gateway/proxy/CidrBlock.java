package dev.waypoint.gateway.proxy;

import java.net.InetAddress;

public record CidrBlock(byte[] network, int prefixLength) {

    public static CidrBlock parse(String cidr) {
        String[] parts = cidr.strip().split("/", 2);
        InetAddress address = IpLiterals.parse(parts[0])
                .orElseThrow(() -> new IllegalArgumentException("not an IP literal: " + cidr));
        int maxBits = address.getAddress().length * 8;
        int bits = parts.length == 2 ? Integer.parseInt(parts[1]) : maxBits;
        if (bits < 0 || bits > maxBits) {
            throw new IllegalArgumentException("invalid prefix length: " + cidr);
        }
        return new CidrBlock(address.getAddress(), bits);
    }

    public boolean contains(InetAddress address) {
        byte[] candidate = address.getAddress();
        if (candidate.length != network.length) {
            return false;
        }
        int fullBytes = prefixLength / 8;
        for (int i = 0; i < fullBytes; i++) {
            if (candidate[i] != network[i]) {
                return false;
            }
        }
        int remainingBits = prefixLength % 8;
        if (remainingBits == 0) {
            return true;
        }
        int mask = (0xFF << (8 - remainingBits)) & 0xFF;
        return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
    }
}
