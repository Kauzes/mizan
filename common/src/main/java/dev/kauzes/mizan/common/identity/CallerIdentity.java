package dev.kauzes.mizan.common.identity;

import java.util.List;

/**
 * How an established identity travels from the gateway to a service, and what a token calls
 * the same things.
 *
 * <p>These headers are trusted downstream, which is only safe because the gateway removes
 * whatever the caller sent under these names before setting its own. That stripping is the
 * whole reason this list exists in one place: a header added here and forgotten there is a
 * header a caller can forge.
 *
 * <p>Note what is deliberately not here. Credentials a caller is meant to send, such as the
 * API key headers MIZ-32 introduces, are not identity headers and must not be stripped, so
 * this is an explicit list rather than an {@code X-Mizan-} prefix rule.
 */
public final class CallerIdentity {

    /** The authenticated user's id. */
    public static final String USER_HEADER = "X-Mizan-User";

    /** The merchant that user acts for. Every tenant scoped query starts here. */
    public static final String MERCHANT_HEADER = "X-Mizan-Merchant";

    /** The user's roles, comma separated. */
    public static final String ROLES_HEADER = "X-Mizan-Roles";

    /**
     * What kind of caller this is: {@code USER} for a person signed in to the console,
     * {@code API_KEY} for a merchant's own server. A service that must not be automated, or
     * must only be automated, reads this rather than guessing from the roles.
     */
    public static final String PRINCIPAL_HEADER = "X-Mizan-Principal";

    /** Everything the gateway sets, and therefore everything it has to strip first. */
    public static final List<String> HEADERS =
            List.of(USER_HEADER, MERCHANT_HEADER, ROLES_HEADER, PRINCIPAL_HEADER);

    /** The claim naming the merchant, in an access token. */
    public static final String MERCHANT_CLAIM = "merchant";

    /** The claim listing the roles, in an access token. */
    public static final String ROLES_CLAIM = "roles";

    private CallerIdentity() {
    }
}
