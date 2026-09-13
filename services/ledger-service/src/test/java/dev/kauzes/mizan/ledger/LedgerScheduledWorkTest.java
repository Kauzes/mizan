package dev.kauzes.mizan.ledger;

import dev.kauzes.mizan.test.ScheduledWorkTest;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * This service checks its own books on a timer, and nothing else does it for it.
 *
 * <p>The guard matters more here than anywhere: the failure it catches is the platform
 * reporting that the ledger is sound because nothing ever went and looked, which is a worse
 * state than having no check at all.
 */
@SpringBootTest
class LedgerScheduledWorkTest extends ScheduledWorkTest {
}
