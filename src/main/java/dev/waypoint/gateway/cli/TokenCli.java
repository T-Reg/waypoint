package dev.waypoint.gateway.cli;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.Ed25519Signer;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.waypoint.gateway.auth.TokenTypes;
import dev.waypoint.gateway.identity.GatewayKeyStore;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Development stand-in for a token service. It holds the issuer's private key; the gateway only
 * ever gets the public key. Run with {@code -Dloader.main=dev.waypoint.gateway.cli.TokenCli}.
 */
public final class TokenCli {

    static final Duration MAX_CUSTOMER_TTL = Duration.ofHours(24);
    static final Duration MAX_ADMIN_TTL = Duration.ofHours(1);
    private static final Pattern TTL = Pattern.compile("(\\d{1,6})([smh])");
    private static final String USAGE = """
            Usage:
              token-cli keygen --out-dir DIR --name NAME
              token-cli issue --key PRIVATE_JWK --tenant TID --sub SUB [--scope "a b"] [--ttl 15m] [--issuer ISS] [--audience gateway]
              token-cli issue-admin --key PRIVATE_JWK --sub SUB --scope "admin:routes" [--ttl 10m] [--issuer ISS] [--audience gateway-admin]
            """;

    private final PrintStream out;
    private final PrintStream err;
    private final Clock clock;

    TokenCli(PrintStream out, PrintStream err, Clock clock) {
        this.out = out;
        this.err = err;
        this.clock = clock;
    }

    public static void main(String[] args) {
        System.exit(new TokenCli(System.out, System.err, Clock.systemUTC()).run(args));
    }

    int run(String[] args) {
        if (args.length == 0) {
            err.print(USAGE);
            return 2;
        }
        try {
            Map<String, String> options = parseOptions(args);
            switch (args[0]) {
                case "keygen" -> keygen(Path.of(required(options, "out-dir")), required(options, "name"));
                case "issue" -> out.println(issue(options, TokenTypes.CUSTOMER, "waypoint-issuer", "gateway", "15m", MAX_CUSTOMER_TTL, true));
                case "issue-admin" -> out.println(issue(options, TokenTypes.ADMIN, "waypoint-admin-issuer", "gateway-admin", "10m", MAX_ADMIN_TTL, false));
                default -> throw new IllegalArgumentException("unknown command: " + args[0]);
            }
            return 0;
        } catch (IllegalArgumentException | IOException | ParseException | JOSEException e) {
            err.println("error: " + e.getMessage());
            err.print(USAGE);
            return 2;
        }
    }

    private void keygen(Path dir, String name) throws IOException {
        if (!name.matches("[a-z0-9_-]{1,32}")) {
            throw new IllegalArgumentException("--name must match [a-z0-9_-]{1,32}");
        }
        Files.createDirectories(dir);
        OctetKeyPair key = GatewayKeyStore.generate();
        Path privateFile = dir.resolve(name + ".private.jwk.json");
        Path publicFile = dir.resolve(name + ".public.jwk.json");
        if (Files.exists(privateFile)) {
            throw new IllegalArgumentException(privateFile + " already exists; refusing to overwrite");
        }
        Files.writeString(privateFile, key.toJSONString());
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(privateFile, PosixFilePermissions.fromString("rw-------"));
        }
        Files.writeString(publicFile, key.toPublicJWK().toJSONString());
        out.println("wrote " + privateFile + " (keep secret) and " + publicFile);
    }

    private String issue(Map<String, String> options, JOSEObjectType type, String defaultIssuer, String defaultAudience,
                         String defaultTtl, Duration maxTtl, boolean customer) throws IOException, ParseException, JOSEException {
        JWK jwk = JWK.parse(Files.readString(Path.of(required(options, "key"))));
        if (!(jwk instanceof OctetKeyPair key) || !key.isPrivate()) {
            throw new IllegalArgumentException("--key must be a private Ed25519 JWK");
        }
        Duration ttl = parseTtl(options.getOrDefault("ttl", defaultTtl));
        if (ttl.compareTo(maxTtl) > 0) {
            throw new IllegalArgumentException("--ttl must not exceed " + maxTtl);
        }
        Instant now = clock.instant();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(options.getOrDefault("issuer", defaultIssuer))
                .audience(options.getOrDefault("audience", defaultAudience))
                .subject(required(options, "sub"))
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now))
                .expirationTime(Date.from(now.plus(ttl)))
                .jwtID(UUID.randomUUID().toString());
        if (customer) {
            claims.claim("tid", required(options, "tenant"));
        }
        String scope = customer ? options.get("scope") : required(options, "scope");
        if (scope != null && !scope.isBlank()) {
            claims.claim("scope", scope.strip());
        }
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.Ed25519).type(type).keyID(key.getKeyID()).build();
        SignedJWT jwt = new SignedJWT(header, claims.build());
        jwt.sign(new Ed25519Signer(key));
        return jwt.serialize();
    }

    static Duration parseTtl(String text) {
        Matcher matcher = TTL.matcher(text);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("--ttl must look like 30s, 15m or 1h");
        }
        long amount = Long.parseLong(matcher.group(1));
        Duration ttl = switch (matcher.group(2)) {
            case "s" -> Duration.ofSeconds(amount);
            case "m" -> Duration.ofMinutes(amount);
            default -> Duration.ofHours(amount);
        };
        if (ttl.isZero()) {
            throw new IllegalArgumentException("--ttl must be positive");
        }
        return ttl;
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (!args[i].startsWith("--") || i + 1 >= args.length) {
                throw new IllegalArgumentException("expected --option value, got: " + args[i]);
            }
            options.put(args[i].substring(2), args[++i]);
        }
        return options;
    }

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("--" + name + " is required");
        }
        return value;
    }
}
