package dev.kauzes.mizan.ledger.integrity;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

/**
 * The integrity check, as something an operator can run against a real deployment rather than
 * only something a test does.
 *
 * <p>An actuator endpoint rather than an API route: this is a question about the whole
 * ledger, not about one merchant's books, so there is no merchant in the path to scope it to
 * and no merchant who should be asking. It is reachable through the gateway's internal route,
 * which needs a token since MIZ-30, and is not on the public list.
 *
 * <p>Deliberately not a health indicator. Drift is serious enough to wake somebody and not a
 * reason to take the service out of rotation: a ledger that has drifted is one nobody should
 * be able to write to less than one nobody can read.
 */
@Component
@Endpoint(id = "ledgerintegrity")
public class LedgerIntegrityEndpoint {

    private final LedgerIntegrityService integrity;

    public LedgerIntegrityEndpoint(LedgerIntegrityService integrity) {
        this.integrity = integrity;
    }

    @ReadOperation
    public IntegrityReport check() {
        return integrity.check();
    }
}
