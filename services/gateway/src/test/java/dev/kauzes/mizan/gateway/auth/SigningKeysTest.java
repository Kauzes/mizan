package dev.kauzes.mizan.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * The gateway asks identity for its keys, and must not ask on every request. It also has to
 * notice a key it has never seen, because that is what a rotation looks like from here.
 */
class SigningKeysTest {

    private final RSAKey original = freshKey();
    private final RSAKey rotated = freshKey();

    private final AtomicInteger fetches = new AtomicInteger();
    private final MovableClock clock = new MovableClock(Instant.parse("2026-08-30T12:00:00Z"));

    @Test
    void fetchesOnceAndThenAnswersFromWhatItHolds() {
        SigningKeys keys = keysServing(original);

        assertThat(keys.withId(original.getKeyID()).block()).isPresent();
        assertThat(keys.withId(original.getKeyID()).block()).isPresent();
        assertThat(keys.withId(original.getKeyID()).block()).isPresent();

        assertThat(fetches.get()).as("a cached key set is not fetched again").isEqualTo(1);
    }

    @Test
    void fetchesAgainForAKeyItHasNeverSeen() {
        // Identity has one key, and gains a second while the gateway is holding the first.
        SigningKeys keys = keysServing(original);
        assertThat(keys.withId(original.getKeyID()).block()).isPresent();

        served.add(rotated);
        clock.advance(Duration.ofSeconds(31));

        assertThat(keys.withId(rotated.getKeyID()).block())
                .as("a key issued after the last fetch is what a rotation looks like")
                .isPresent();
        assertThat(fetches.get()).isEqualTo(2);
    }

    @Test
    void refusesToKeepFetchingForKeysThatDoNotExist() {
        SigningKeys keys = keysServing(original);

        keys.withId(original.getKeyID()).block();
        clock.advance(Duration.ofSeconds(31));

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(keys.withId("a-key-nobody-has").block()).isEmpty();
        }

        assertThat(fetches.get())
                .as("one look for a key that might be new, then no more until the floor passes")
                .isEqualTo(2);
    }

    @Test
    void fetchesAgainOnceTheHeldKeysAreStale() {
        SigningKeys keys = keysServing(original);

        keys.withId(original.getKeyID()).block();
        clock.advance(Duration.ofMinutes(11));
        keys.withId(original.getKeyID()).block();

        assertThat(fetches.get()).isEqualTo(2);
    }

    /** What identity is publishing right now, which a rotation changes. */
    private final java.util.List<RSAKey> served = new java.util.ArrayList<>();

    private SigningKeys keysServing(RSAKey... initial) {
        served.addAll(java.util.Arrays.asList(initial));

        WebClient client = WebClient.builder()
                .exchangeFunction(request -> {
                    fetches.incrementAndGet();
                    String body = new JWKSet(served.stream()
                                    .map(key -> (com.nimbusds.jose.jwk.JWK) key.toPublicJWK())
                                    .toList())
                            .toString();
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                            .body(body)
                            .build());
                })
                .build();

        return new SigningKeys(
                client,
                new AuthenticationProperties("https://mizan.local/identity", "http://identity/jwks", null, null),
                clock);
    }

    private static RSAKey freshKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var pair = generator.generateKeyPair();
            RSAKey withoutId = new RSAKey.Builder((RSAPublicKey) pair.getPublic()).build();
            return new RSAKey.Builder(withoutId)
                    .keyID(withoutId.computeThumbprint().toString())
                    .build();
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Time has to move for a cache to be worth testing. */
    private static final class MovableClock extends Clock {

        private Instant now;

        private MovableClock(Instant start) {
            this.now = start;
        }

        private void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
