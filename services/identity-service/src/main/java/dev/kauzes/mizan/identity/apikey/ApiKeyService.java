package dev.kauzes.mizan.identity.apikey;

import dev.kauzes.mizan.common.error.NotFoundException;
import dev.kauzes.mizan.common.error.UnauthorizedException;
import dev.kauzes.mizan.common.error.UnprocessableException;
import dev.kauzes.mizan.common.identity.RequestSigning;
import dev.kauzes.mizan.identity.apikey.ApiKeyResponses.ApiKeyResponse;
import dev.kauzes.mizan.identity.apikey.ApiKeyResponses.IssueKeyRequest;
import dev.kauzes.mizan.identity.apikey.ApiKeyResponses.IssuedKeyResponse;
import dev.kauzes.mizan.identity.merchant.Merchant;
import dev.kauzes.mizan.identity.merchant.MerchantRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Issuing keys, and deciding whether a signed request really came from one. */
@Service
public class ApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);

    /** One answer for every way a signed request can fail, so none can be told apart. */
    private static final String REFUSED = "The credentials are not valid.";

    private static final String KEY_PREFIX = "mzk_";
    private static final String SECRET_PREFIX = "mzs_";
    private static final int KEY_ID_BYTES = 12;
    private static final int SECRET_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final ApiKeyRepository keys;
    private final MerchantRepository merchants;
    private final SecretCipher cipher;
    private final Duration clockSkew;
    private final Clock clock;

    @Autowired
    public ApiKeyService(
            ApiKeyRepository keys,
            MerchantRepository merchants,
            SecretCipher cipher,
            @Value("${mizan.security.api-keys.clock-skew:5m}") Duration clockSkew) {
        this(keys, merchants, cipher, clockSkew, Clock.systemUTC());
    }

    ApiKeyService(
            ApiKeyRepository keys,
            MerchantRepository merchants,
            SecretCipher cipher,
            Duration clockSkew,
            Clock clock) {
        this.keys = keys;
        this.merchants = merchants;
        this.cipher = cipher;
        this.clockSkew = clockSkew;
        this.clock = clock;
    }

    @Transactional
    public IssuedKeyResponse issue(UUID merchantId, IssueKeyRequest request) {
        Merchant merchant = merchants
                .findById(merchantId)
                .orElseThrow(() -> new NotFoundException("No merchant with that id."));

        return issueFor(merchant, request.name().trim(), request.role(), null);
    }

    @Transactional(readOnly = true)
    public List<ApiKeyResponse> list(UUID merchantId) {
        return keys.findByMerchantIdOrderByCreatedAtAsc(merchantId).stream()
                .map(ApiKeyResponse::of)
                .toList();
    }

    /**
     * Issues a replacement and revokes the original in one step.
     *
     * <p>Both halves together, because a rotation done as two calls has a gap in the middle
     * where the merchant either has no working key or has two, depending which they do first.
     */
    @Transactional
    public IssuedKeyResponse rotate(UUID merchantId, UUID keyId) {
        ApiKey previous = mine(merchantId, keyId);
        if (previous.isRevoked()) {
            throw new UnprocessableException("That key is already revoked. Issue a new one.");
        }

        IssuedKeyResponse replacement =
                issueFor(previous.merchant(), previous.name(), previous.role(), previous);
        previous.revoke(clock.instant());

        log.info("rotated key {} of merchant {}", previous.keyId(), merchantId);
        return replacement;
    }

    @Transactional
    public ApiKeyResponse revoke(UUID merchantId, UUID keyId) {
        ApiKey key = mine(merchantId, keyId);
        key.revoke(clock.instant());

        log.info("revoked key {} of merchant {}", key.keyId(), merchantId);
        return ApiKeyResponse.of(key);
    }

    /**
     * Whether this signature was made by the key it names, for this request, recently.
     *
     * <p>Consulted on every signed request rather than cached, which is what makes a
     * revocation take effect at once instead of whenever a cache would have expired.
     */
    @Transactional
    public SignedRequest.Verified verify(SignedRequest.Verification presented) {
        ApiKey key = keys.findByKeyId(presented.keyId())
                .orElseThrow(() -> new UnauthorizedException(REFUSED));

        if (key.isRevoked()) {
            throw new UnauthorizedException(REFUSED);
        }

        Instant signedAt = Instant.ofEpochSecond(presented.timestamp());
        Duration drift = Duration.between(signedAt, clock.instant()).abs();
        if (drift.compareTo(clockSkew) > 0) {
            // Either a replay of something captured earlier, or a clock nobody has set. The
            // window is configuration because which of those it is depends on the deployment.
            throw new UnauthorizedException(REFUSED);
        }

        String secret;
        try {
            secret = cipher.decrypt(key.secretEncrypted(), key.keyId());
        } catch (SecretCipher.SecretUnavailableException unopenable) {
            // The stored value does not belong to this key, or cannot be read at all. That
            // is ours to investigate and not the caller's to be told about, so it is logged
            // here and refused like any other bad credential.
            log.error("the stored secret for key {} could not be opened", key.keyId(), unopenable);
            throw new UnauthorizedException(REFUSED);
        }

        String expected = RequestSigning.sign(
                secret,
                RequestSigning.canonicalRequest(
                        presented.method(),
                        presented.path(),
                        presented.timestamp(),
                        presented.bodyHash()));

        if (!RequestSigning.matches(expected, presented.signature())) {
            throw new UnauthorizedException(REFUSED);
        }

        key.used(clock.instant());
        return new SignedRequest.Verified(
                key.id(), key.merchantId(), key.keyId(), key.role());
    }

    private IssuedKeyResponse issueFor(
            Merchant merchant,
            String name,
            dev.kauzes.mizan.common.identity.Role role,
            ApiKey previous) {

        String secret = SECRET_PREFIX + random(SECRET_BYTES);
        String keyId = KEY_PREFIX + random(KEY_ID_BYTES);
        ApiKey key = new ApiKey(
                merchant, keyId, name, cipher.encrypt(secret, keyId), role);
        if (previous != null) {
            keys.saveAndFlush(key);
            key.replaced(previous);
        }
        keys.save(key);

        log.info("issued key {} for merchant {} as {}", key.keyId(), merchant.id(), role);
        return new IssuedKeyResponse(ApiKeyResponse.of(key), secret);
    }

    private ApiKey mine(UUID merchantId, UUID keyId) {
        return keys.findByIdAndMerchantId(keyId, merchantId)
                .orElseThrow(() -> new NotFoundException("No API key with that id."));
    }

    private static String random(int bytes) {
        byte[] material = new byte[bytes];
        RANDOM.nextBytes(material);
        return ENCODER.encodeToString(material);
    }
}
