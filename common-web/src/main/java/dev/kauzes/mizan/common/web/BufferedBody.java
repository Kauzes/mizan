package dev.kauzes.mizan.common.web;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * A request whose body can be read more than once.
 *
 * <p>Needed because the decision about a repeated request has to be made before the handler
 * runs, and part of that decision is whether this is the same request as last time — which
 * means reading the body while the handler still expects to read it itself.
 *
 * <p>Spring's own caching wrapper records what was read, which is empty at the point the
 * decision is made. This one reads first and hands the same bytes to whoever asks next.
 */
final class BufferedBody extends HttpServletRequestWrapper {

    private final byte[] body;

    private BufferedBody(HttpServletRequest request, byte[] body) {
        super(request);
        this.body = body;
    }

    static BufferedBody of(HttpServletRequest request, int limit) throws IOException {
        byte[] read = request.getInputStream().readNBytes(limit);
        return new BufferedBody(request, read);
    }

    byte[] body() {
        return body;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream bytes = new ByteArrayInputStream(body);

        return new ServletInputStream() {

            @Override
            public int read() {
                return bytes.read();
            }

            @Override
            public boolean isFinished() {
                return bytes.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException("this body is already here");
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(
                new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
}
