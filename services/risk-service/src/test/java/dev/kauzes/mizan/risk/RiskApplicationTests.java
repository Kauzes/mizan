package dev.kauzes.mizan.risk;

import dev.kauzes.mizan.test.MizanIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** Booting means migrating, so this needs the real database the service runs against. */
@SpringBootTest
class RiskApplicationTests extends MizanIntegrationTest {

    @Test
    void contextLoads() {
    }
}
