package dev.kauzes.mizan.identity;

import dev.kauzes.mizan.test.SchemaMigrationTest;
import org.springframework.boot.test.context.SpringBootTest;

/** The identity service against the schema contract every service with a database has to meet. */
@SpringBootTest
class IdentitySchemaMigrationTest extends SchemaMigrationTest {
}
