package dev.kauzes.mizan.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
class PostgresContainerTest {

    @Test
    void runsTheSameImageComposeRuns() {
        assertThat(MizanContainers.postgres().getDockerImageName())
                .isEqualTo(PlatformImages.postgres());
    }

    @Test
    void acceptsRealSql() throws SQLException {
        PostgreSQLContainer postgres = MizanContainers.postgres();

        try (Connection connection = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {

            statement.execute("create table if not exists harness_probe (id bigint primary key)");
            statement.execute("insert into harness_probe (id) values (1) on conflict do nothing");

            try (ResultSet rows = statement.executeQuery("select count(*) from harness_probe")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isEqualTo(1L);
            }
        }
    }

    @Test
    void isTheSameContainerEveryTestClassSees() {
        SharedContainerRecorder.recordAndAssertSame(
                "postgres", MizanContainers.postgres().getContainerId());
    }
}
