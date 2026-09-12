package dev.kauzes.mizan.settlement;

import java.util.Map;
import java.util.UUID;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

/**
 * What is owed and unpaid, and the one way to pay it.
 *
 * <p>An actuator endpoint rather than an API route, like closing a day and like MIZ-53's stuck
 * payments: deciding that money leaves this platform is the platform's own business, and no
 * merchant should be able to decide when they get paid. Reachable only through the gateway's
 * internal route, which needs a token no merchant has.
 *
 * <p>Its own endpoint rather than another verb on the settlements one, because an actuator
 * endpoint has one write operation and squeezing two unrelated verbs behind a discriminator is
 * how MIZ-53's operator endpoint ended up with a string nobody can guess.
 */
@Component
@Endpoint(id = "payouts")
public class PayoutEndpoint {

    private final Payouts payouts;

    public PayoutEndpoint(Payouts payouts) {
        this.payouts = payouts;
    }

    @ReadOperation
    public Map<String, Object> whatIsUnpaid() {
        return Map.of("unpaid", payouts.unpaid(200));
    }

    /**
     * Pays one batch.
     *
     * <p>Repeatable: asking twice pays once, and says which of the two it was. That is what
     * makes it safe to hand to somebody who has just refreshed a page and is not sure whether
     * their click landed.
     */
    @WriteOperation
    public Map<String, Object> pay(@Selector String batchId) {
        return payouts.pay(UUID.fromString(batchId));
    }
}
