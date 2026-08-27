package dev.kauzes.mizan.payment;

import dev.kauzes.mizan.test.SchemaMigrationTest;
import org.springframework.boot.test.context.SpringBootTest;

/** The payment service against the schema contract every service with a database has to meet. */
@SpringBootTest
class PaymentSchemaMigrationTest extends SchemaMigrationTest {
}
