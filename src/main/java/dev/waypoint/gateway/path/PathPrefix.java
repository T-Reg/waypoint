package dev.waypoint.gateway.path;

/** Segment-aware prefix matching: {@code /api} matches {@code /api} and {@code /api/x}, never {@code /apix}. */
public final class PathPrefix {

    private PathPrefix() {}

    public static boolean matches(String prefix, String canonicalPath) {
        if (prefix.equals("/")) {
            return true;
        }
        return canonicalPath.startsWith(prefix)
                && (canonicalPath.length() == prefix.length() || canonicalPath.charAt(prefix.length()) == '/');
    }

    /** A valid prefix is already canonical. */
    public static boolean isValid(String prefix) {
        if (prefix == null) {
            return false;
        }
        try {
            return PathNormalizer.normalize(prefix).equals(prefix);
        } catch (InvalidPathException e) {
            return false;
        }
    }

    public static String strip(String prefix, String canonicalPath) {
        if (prefix.equals("/")) {
            return canonicalPath;
        }
        String rest = canonicalPath.substring(prefix.length());
        return rest.isEmpty() ? "/" : rest;
    }
}
