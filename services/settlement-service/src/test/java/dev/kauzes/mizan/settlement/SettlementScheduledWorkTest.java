package dev.kauzes.mizan.settlement;

import dev.kauzes.mizan.test.ScheduledWorkTest;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * This service closes days on a schedule, and nothing else does it for it.
 *
 * <p>The guard exists because a service once shipped with @Scheduled methods and no
 * @EnableScheduling, and every test passed while the work silently never ran. For settlement
 * that failure mode is a merchant who is never paid.
 */
@SpringBootTest
class SettlementScheduledWorkTest extends ScheduledWorkTest {
}
