package dev.kauzes.mizan.gateway.auth;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * The public keys identity publishes, cached.
 *
 * <p>Fetched over HTTP rather than shared as configuration, so a key rotation is something
 * identity does alone. The gateway holds nothing that could sign a token.
 *
 * <p>Fetching is done with the reactive client rather than the JOSE library's own remote key
 * set, because that one blocks, and blocking the event loop of the component every request
 * passes through is a poor place to do it.
 */
@Component
public class SigningKeys {

    private static final Logger log = LoggerFactory.getLogger(SigningKeys.class);

    private final WebClient http;
    private final AuthenticationProperties properties;
    private final Clock clock;

    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();

    private record Snapshot(JWKSet keys, Instant fetchedAt) {
    }

    @Autowired
    public SigningKeys(WebClient.Builder http, AuthenticationProperties properties) {
        this(http.build(), properties, Clock.systemUTC());
    }

    SigningKeys(WebClient http, AuthenticationProperties properties, Clock clock) {
        this.http = http;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * The key with this id, fetching the key set if it is not cached, is stale, or does not
     * contain the id. A token signed by a key issued after the last fetch is the ordinary
     * case during a rotation, and has to work without a restart.
     */
    public Mono<Optional<JWK>> withId(String keyId) {
        Snapshot current = snapshot.get();

        if (current != null && !isStale(current) && current.keys().getKeyByKeyId(keyId) != null) {
            return Mono.just(Optional.of(current.keys().getKeyByKeyId(keyId)));
        }

        if (current != null && !mayRefetch(current)) {
            // Refused a moment ago and asked again: answer from what is held rather than
            // letting unknown key ids turn into traffic.
            return Mono.just(Optional.ofNullable(current.keys().getKeyByKeyId(keyId)));
        }

        return fetch().map(keys -> Optional.ofNullable(keys.getKeyByKeyId(keyId)));
    }

    private Mono<JWKSet> fetch() {
        return http.get()
                .uri(properties.jwkSetUri())
                .retrieve()
                .bodyToMono(String.class)
                .map(body -> {
                    try {
                        return JWKSet.parse(body);
                    } catch (java.text.ParseException malformed) {
                        throw new IllegalStateException(
                                "the key set at " + properties.jwkSetUri() + " is not a JWKS",
                                malformed);
                    }
                })
                .doOnNext(keys -> {
                    snapshot.set(new Snapshot(keys, clock.instant()));
                    log.debug("fetched {} signing keys", keys.getKeys().size());
                })
                .doOnError(failure ->
                        log.warn("could not fetch signing keys from {}", properties.jwkSetUri()));
    }

    private boolean isStale(Snapshot held) {
        return elapsedSince(held.fetchedAt()).compareTo(properties.keyCacheTtl()) >= 0;
    }

    private boolean mayRefetch(Snapshot held) {
        return elapsedSince(held.fetchedAt()).compareTo(properties.minimumRefreshInterval()) >= 0;
    }

    private Duration elapsedSince(Instant moment) {
        return Duration.between(moment, clock.instant());
    }
}
