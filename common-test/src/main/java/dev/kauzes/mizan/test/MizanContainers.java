package dev.kauzes.mizan.test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Containers shared by every integration test in a JVM. Each one starts on first use and
 * is never stopped, so a suite pays the startup cost once rather than per test class.
 * Testcontainers reaps them when the JVM exits.
 *
 * <p>One Postgres holds every service's database, the way one Postgres does in the compose
 * stack. Services do not share a database, so a test gets the database its service owns and
 * never sees another service's tables.
 */
public final class MizanContainers {

    private MizanContainers() {
    }

    public static PostgreSQLContainer postgres() {
        return Postgres.INSTANCE;
    }

    public static KafkaContainer kafka() {
        return Kafka.INSTANCE;
    }

    /** The url of a database on the shared Postgres, created if it is not there yet. */
    public static synchronized String database(String name) {
        String quoted = quoted(name);
        if (!databaseExists(name)) {
            administer("create database " + quoted);
        }
        return jdbcUrl(name);
    }

    /**
     * The url of a database with nothing in it, dropped first if a previous test left one
     * behind. A migration that has already run proves nothing about applying to an empty
     * database, which is what this is for.
     */
    public static synchronized String emptyDatabase(String name) {
        String quoted = quoted(name);
        administer("drop database if exists " + quoted + " with (force)");
        administer("create database " + quoted);
        return jdbcUrl(name);
    }

    private static String jdbcUrl(String database) {
        PostgreSQLContainer postgres = postgres();
        return "jdbc:postgresql://%s:%d/%s"
                .formatted(postgres.getHost(), postgres.getFirstMappedPort(), database);
    }

    private static boolean databaseExists(String name) {
        try (Connection connection = administrativeConnection();
                Statement statement = connection.createStatement();
                ResultSet found = statement.executeQuery(
                        "select 1 from pg_database where datname = '" + name + "'")) {
            return found.next();
        } catch (SQLException e) {
            throw new IllegalStateException("could not look up database " + name, e);
        }
    }

    private static void administer(String sql) {
        try (Connection connection = administrativeConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("could not run " + sql, e);
        }
    }

    private static Connection administrativeConnection() throws SQLException {
        PostgreSQLContainer postgres = postgres();
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    /**
     * Database names come from configuration a service owns, not from a caller's input, but
     * they still land in SQL that cannot be parameterised. Anything but a plain identifier
     * is refused rather than quoted and hoped for.
     */
    private static String quoted(String name) {
        if (!name.matches("[a-z][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("not a usable database name: " + name);
        }
        return "\"" + name + "\"";
    }

    private static final class Postgres {

        private static final PostgreSQLContainer INSTANCE =
                new PostgreSQLContainer(DockerImageName.parse(PlatformImages.postgres()))
                        .withDatabaseName("mizan")
                        .withUsername("mizan")
                        .withPassword("mizan");

        static {
            INSTANCE.start();
        }
    }

    private static final class Kafka {

        private static final KafkaContainer INSTANCE =
                new KafkaContainer(DockerImageName.parse(PlatformImages.kafka()));

        static {
            INSTANCE.start();
        }
    }
}
