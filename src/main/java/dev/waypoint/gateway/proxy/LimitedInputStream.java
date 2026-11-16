package dev.waypoint.gateway.proxy;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Enforces the body-size limit while streaming, for chunked bodies with no Content-Length. */
final class LimitedInputStream extends FilterInputStream {

    private final long limit;
    private long count;

    LimitedInputStream(InputStream in, long limit) {
        super(in);
        this.limit = limit;
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b >= 0) {
            add(1);
        }
        return b;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        int n = super.read(buffer, offset, length);
        if (n > 0) {
            add(n);
        }
        return n;
    }

    private void add(long n) throws RequestTooLargeException {
        count += n;
        if (count > limit) {
            throw new RequestTooLargeException();
        }
    }
}
