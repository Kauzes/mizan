package dev.kauzes.mizan.identity.token;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public half of the signing key, in the form every JWT library already knows how to
 * read.
 *
 * <p>This is what lets the gateway verify a token in MIZ-30 without holding anything that
 * could mint one. A shared secret would have been less work and would have meant that
 * whoever can check a token can also issue one, which is a poor trade for the component
 * facing the internet.
 */
@RestController
@Tag(name = "Keys", description = "The public key access tokens are signed with")
public class JwksController {

    private final RSAKey signingKey;

    public JwksController(RSAKey signingKey) {
        this.signingKey = signingKey;
    }

    @GetMapping(path = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "The signing keys, public halves only",
            description = "A JWKS. Verifiers fetch this rather than being handed a secret.")
    public Map<String, Object> jwks() {
        return new JWKSet(signingKey.toPublicJWK()).toJSONObject();
    }
}
