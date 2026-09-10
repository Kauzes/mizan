package dev.kauzes.mizan.identity.token;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.ResponseCookie;

/**
 * Where a browser's refresh token lives.
 *
 * <p>A cookie the page cannot read, rather than local storage. A refresh token is a credential
 * for days, and local storage is readable by any script that gets onto the page: one injected
 * dependency, one third party widget, one cross site scripting hole, and the session is
 * copyable rather than merely usable. A HttpOnly cookie can still be *used* by an attacker who
 * has script on the page, but it cannot be taken away and used later from somewhere else,
 * which is the difference between an incident and a breach.
 *
 * <p>The API keeps returning the token in the body as well, so a server side client — the
 * scripts in this repository among them — is unaffected. This exists for the one caller that
 * cannot be trusted to store a secret, which is a browser.
 *
 * @param name what the cookie is called
 * @param secure whether it is only sent over HTTPS. True everywhere it matters; the local
 *     Compose stack speaks plain HTTP and turns it off, which is the one place that is fine
 * @param sameSite {@code Strict} by default, which is also this platform's CSRF defence for
 *     the refresh endpoint: a cookie a browser will not attach to a cross site request cannot
 *     be spent by a form on somebody else's page
 */
@ConfigurationProperties(prefix = "mizan.security.session-cookie")
public record SessionCookie(String name, boolean secure, String sameSite) {

    /**
     * Scoped to the token endpoints and nowhere else.
     *
     * <p>Every other request a console makes carries an access token in a header, so there is
     * no reason for the refresh token to travel with them. A credential that is sent on every
     * request is a credential logged by every proxy.
     */
    private static final String PATH = "/api/v1/tokens";

    public SessionCookie {
        name = name == null || name.isBlank() ? "mizan_refresh" : name;
        sameSite = sameSite == null || sameSite.isBlank() ? "Strict" : sameSite;
    }

    /** The cookie carrying a refresh token, for as long as that token is good for. */
    public ResponseCookie carrying(String refreshToken, Duration lifetime) {
        return base(refreshToken).maxAge(lifetime).build();
    }

    /**
     * The cookie that removes it.
     *
     * <p>Same name, same path, same flags, empty and already expired. A browser matches a
     * cookie by name and path, so a deletion that differs in either leaves the original in
     * place — and a sign out that did not sign anybody out is the worst kind of bug here.
     */
    public ResponseCookie gone() {
        return base("").maxAge(Duration.ZERO).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path(PATH);
    }
}
