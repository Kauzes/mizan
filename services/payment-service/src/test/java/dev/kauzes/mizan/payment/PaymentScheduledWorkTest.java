package dev.kauzes.mizan.payment;

import dev.kauzes.mizan.test.ScheduledWorkTest;
import org.springframework.boot.test.context.SpringBootTest;

/** This service resolves unknown authorizations, drains an outbox and finishes refunds. */
@SpringBootTest
class PaymentScheduledWorkTest extends ScheduledWorkTest {
}
