package dev.kauzes.mizan.test;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;

/**
 * Points a service under test at its own database on the shared Postgres container.
 *
 * <p>The database name is not repeated here. It is read out of the datasource url the
 * service ships in its own configuration, and only the host and port are replaced, so a
 * test cannot end up on a different database than the one the service is deployed against.
 * A service that configures no datasource is left alone.
 *
 * <p>The container starts when something first reads one of these properties, not when the
 * context is initialised, so a test that needs no database never pays for Postgres.
 */
public final class ServiceDatabaseInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    static final String URL = "spring.datasource.url";
    static final String USERNAME = "spring.datasource.username";
    static final String PASSWORD = "spring.datasource.password";

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        ConfigurableEnvironment environment = context.getEnvironment();
        String configured = environment.getProperty(URL);
        if (configured == null || configured.isBlank()) {
            return;
        }
        environment
                .getPropertySources()
                .addFirst(new SharedPostgres(databaseNameIn(configured)));
    }

    static String databaseNameIn(String jdbcUrl) {
        String withoutParameters = jdbcUrl.split("[?;]", 2)[0];
        String name = withoutParameters.substring(withoutParameters.lastIndexOf('/') + 1);
        if (name.isBlank()) {
            throw new IllegalStateException("no database name in datasource url " + jdbcUrl);
        }
        return name;
    }

    private static final class SharedPostgres extends EnumerablePropertySource<Object> {

        private static final String[] NAMES = {URL, USERNAME, PASSWORD};

        private final String database;

        private SharedPostgres(String database) {
            super("mizanSharedPostgres", new Object());
            this.database = database;
        }

        @Override
        public String[] getPropertyNames() {
            return NAMES.clone();
        }

        @Override
        public Object getProperty(String name) {
            return switch (name) {
                case URL -> MizanContainers.database(database);
                case USERNAME -> MizanContainers.postgres().getUsername();
                case PASSWORD -> MizanContainers.postgres().getPassword();
                default -> null;
            };
        }
    }
}
