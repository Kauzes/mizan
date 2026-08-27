package dev.kauzes.mizan.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.Location;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.contributor.Status;
import org.springframework.core.env.Environment;

/**
 * What every service that owns a database has to prove. A service adds a subclass carrying
 * its own {@code @SpringBootTest}, and gets the whole contract without repeating it.
 *
 * <p>The migrations are run a second time against a database created for the purpose, not
 * against the one the service booted on. Applying cleanly to a database that has already
 * been migrated is not the claim being made.
 */
public abstract class SchemaMigrationTest extends MizanIntegrationTest {

    /** Databases the platform owns rather than any one service. */
    private static final String[] SHARED_DATABASES = {"postgres", "mizan"};

    @Autowired
    private Flyway flyway;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Environment environment;

    @Autowired
    private HealthEndpoint health;

    @Test
    void startsOnADatabaseOfItsOwn() throws SQLException {
        assertThat(database()).isNotIn((Object[]) SHARED_DATABASES);
    }

    @Test
    void appliedItsMigrationsOnStartup() {
        assertThat(flyway.info().applied()).isNotEmpty();
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    void appliesCleanlyToAnEmptyDatabase() throws SQLException {
        Flyway fresh = flywayAgainstAnEmptyDatabase();

        MigrateResult result = fresh.migrate();

        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isPositive();
        assertThat(fresh.validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    void isIdempotentOnASecondRun() throws SQLException {
        Flyway fresh = flywayAgainstAnEmptyDatabase();
        fresh.migrate();

        MigrateResult second = fresh.migrate();

        assertThat(second.success).isTrue();
        assertThat(second.migrationsExecuted).isZero();
        assertThat(fresh.validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    void neverGeneratesSchemaFromEntities() {
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    }

    @Test
    void healthReportsDatabaseConnectivity() {
        HealthDescriptor db = health.healthForPath("db");

        assertThat(db).as("no db component in health").isNotNull();
        assertThat(db.getStatus()).isEqualTo(Status.UP);
        assertThat(health.health().getStatus()).isEqualTo(Status.UP);
    }

    private Flyway flywayAgainstAnEmptyDatabase() throws SQLException {
        String url = MizanContainers.emptyDatabase(database() + "_probe");
        return Flyway.configure()
                .dataSource(
                        url,
                        MizanContainers.postgres().getUsername(),
                        MizanContainers.postgres().getPassword())
                .locations(configuredLocations())
                .load();
    }

    /** The locations the service itself migrates from, so the probe cannot test other files. */
    private String[] configuredLocations() {
        return Arrays.stream(flyway.getConfiguration().getLocations())
                .map(Location::getDescriptor)
                .toArray(String[]::new);
    }

    private String database() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getCatalog();
        }
    }
}
