package dev.kauzes.mizan.notification.webhook;

import dev.kauzes.mizan.common.net.SafeDestination;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Whether this platform is willing to call a merchant's URL, in one place.
 *
 * <p>Asked twice: once when an endpoint is registered, so a merchant is told immediately, and
 * again at the moment of every delivery, because DNS answers to whoever controls it and a name
 * that pointed at their server yesterday can point at ours today. A check that only ran at
 * registration is a check an attacker waits out.
 *
 * <p>One class rather than two calls to {@link SafeDestination}, so the two checks cannot drift
 * apart and so the escape hatch below exists exactly once.
 */
@Component
public class WebhookDestinations {

    private static final Logger log = LoggerFactory.getLogger(WebhookDestinations.class);

    /**
     * Turns the check off. For tests, and for nothing else.
     *
     * <p>A test needs a merchant server it can actually run, which means loopback, which the
     * check refuses — correctly. Rather than let the test reach around the check and prove
     * nothing about the real path, the check is switched off explicitly and loudly, and there
     * is a test that it still bites when it is not.
     */
    private final boolean allowAnything;

    public WebhookDestinations(
            @Value("${mizan.webhooks.allow-any-destination:false}") boolean allowAnything) {

        this.allowAnything = allowAnything;
        if (allowAnything) {
            log.warn(
                    "mizan.webhooks.allow-any-destination is on, so webhook URLs pointing "
                            + "inside this network will be called. This is for tests. Anywhere "
                            + "else it is a way to make this service issue requests on a "
                            + "stranger's behalf.");
        }
    }

    /** @return why this URL will not be called, or empty if it will */
    public Optional<SafeDestination.Refusal> check(String url) {
        return allowAnything ? Optional.empty() : SafeDestination.check(url);
    }
}
