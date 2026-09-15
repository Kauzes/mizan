package dev.kauzes.mizan.merchant.domain

/**
 * A signed in merchant, as this phone holds it.
 *
 * Times are epoch milliseconds on this phone's clock, worked out from the lifetimes the platform
 * gave rather than from the timestamps inside the token. A phone's clock can be wrong by minutes;
 * "expires in 900 seconds, counted from when it arrived" cannot.
 */
data class Session(
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAt: Long,
    val refreshExpiresAt: Long,
    val merchantId: String,
    val userId: String,
)

/** What signing in came to. */
sealed interface SignInOutcome {
    data class SignedIn(val session: Session) : SignInOutcome

    /** The platform refused the email and password. It does not say which was wrong, on purpose. */
    data object Refused : SignInOutcome

    /** Nothing answered: no network, or the platform is not reachable from here. */
    data class Unreachable(val because: String) : SignInOutcome

    /** The platform answered with something that was neither a session nor a refusal. */
    data class Failed(val because: String) : SignInOutcome
}

/** What spending a refresh token came to. */
sealed interface RefreshOutcome {
    data class Renewed(val session: Session) : RefreshOutcome

    /** The platform will not renew this session. It is over, and the merchant signs in again. */
    data object Rejected : RefreshOutcome

    /** Nothing answered. The session may be perfectly good; this phone just cannot tell right now. */
    data class Unreachable(val because: String) : RefreshOutcome
}

/** What asking for a usable access token came to. */
sealed interface AccessOutcome {
    data class Valid(val token: String) : AccessOutcome

    /** There is no session, or the platform ended it. */
    data object SignedOut : AccessOutcome

    /** The token needed renewing and the platform could not be reached to renew it. */
    data class Unreachable(val because: String) : AccessOutcome
}
