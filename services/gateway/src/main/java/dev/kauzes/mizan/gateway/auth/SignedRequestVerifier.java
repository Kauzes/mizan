package dev.kauzes.mizan.gateway.auth;

import dev.kauzes.mizan.common.error.ErrorCode;
import dev.kauzes.mizan.common.error.MizanException;
import dev.kauzes.mizan.common.error.UnauthorizedException;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/**
 * Asks identity whether a signed request holds up.
 *
 * <p>Asked every time rather than cached. The gateway could have been handed the secrets and
 * checked signatures itself, which would be faster and would mean the component facing the
 * internet holds every merchant's signing secret, and that a revoked key would go on working
 * until a cache expired. Neither is worth the round trip saved.
 */
@Component
public class SignedRequestVerifier {

    private static final String REFUSED = "The credentials are not valid.";

    private final WebClient http;
    private final String verificationUri;

    @Autowired
    public SignedRequestVerifier(
            WebClient.Builder http,
            @Value("${mizan.security.api-keys.verification-uri:"
                            + "http://localhost:8081/internal/api-keys/verify}")
                    String verificationUri) {
        this(http.build(), verificationUri);
    }

    SignedRequestVerifier(WebClient http, String verificationUri) {
        this.http = http;
        this.verificationUri = verificationUri;
    }

    public Mono<VerifiedCaller> verify(Verification presented) {
        return http.post()
                .uri(verificationUri)
                .bodyValue(presented)
                .retrieve()
                .bodyToMono(Verified.class)
                .map(verified -> new VerifiedCaller(
                        verified.principalId().toString(),
                        verified.merchantId().toString(),
                        List.of(verified.role()),
                        VerifiedCaller.Principal.API_KEY))
                .onErrorMap(SignedRequestVerifier::asRefusalOrOutage);
    }

    /**
     * A refusal from identity is the caller's problem; anything else is ours. Reporting an
     * outage as bad credentials would send a merchant off to rotate a key that is fine.
     */
    private static Throwable asRefusalOrOutage(Throwable failure) {
        if (failure instanceof WebClientResponseException answered
                && answered.getStatusCode().isSameCodeAs(HttpStatusCode.valueOf(401))) {
            return new UnauthorizedException(REFUSED);
        }
        if (failure instanceof MizanException already) {
            return already;
        }
        return new MizanException(
                ErrorCode.UPSTREAM_UNAVAILABLE,
                "Authentication is temporarily unavailable.",
                failure);
    }

    /** The canonical request, in parts, as the gateway saw it. */
    public record Verification(
            String keyId,
            String signature,
            String method,
            String path,
            long timestamp,
            String bodyHash) {
    }

    private record Verified(UUID principalId, UUID merchantId, String keyId, String role) {
    }
}
