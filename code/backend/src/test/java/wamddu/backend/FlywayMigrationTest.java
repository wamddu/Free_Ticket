package wamddu.backend;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The server creates and changes its schema only through the Flyway migrations and starts with ddl-auto=validate.
 * The application boots here on an empty MySQL database, so a migration that fails or an entity that no longer
 * matches the migrated schema fails in CI instead of on the server. Needs MySQL on port 3308 (CI provides it).
 */
@EnabledIfEnvironmentVariable(named = "RUN_MYSQL_CONCURRENCY", matches = "true")
@SpringBootTest(properties = {
        "spring.datasource.url=" + FlywayMigrationTest.EMPTY_DB_URL,
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.username=root",
        "spring.datasource.password=" + FlywayMigrationTest.PASSWORD,
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.show-sql=false"
})
@DirtiesContext
class FlywayMigrationTest {
    static final String PASSWORD = "concurrency-test";
    private static final String SERVER_URL = "jdbc:mysql://127.0.0.1:3308/?serverTimezone=Asia/Seoul";
    private static final String EMPTY_DB = "free_ticket_migration";
    private static final String EXISTING_DB = "free_ticket_baseline";
    private static final String SEED_DB = "free_ticket_seed";
    static final String EMPTY_DB_URL = "jdbc:mysql://127.0.0.1:3308/" + EMPTY_DB + "?serverTimezone=Asia/Seoul";
    private static final String EXISTING_DB_URL = "jdbc:mysql://127.0.0.1:3308/" + EXISTING_DB + "?serverTimezone=Asia/Seoul";
    private static final String SEED_DB_URL = "jdbc:mysql://127.0.0.1:3308/" + SEED_DB + "?serverTimezone=Asia/Seoul";
    private static final String STATUS_PAID_AT_INDEX = "idx_orders_status_paid_at";

    @Autowired
    Flyway flyway;

    // Runs before the Spring context starts, so the application migrates a database with no tables at all.
    @BeforeAll
    static void createEmptyDatabases() throws SQLException {
        try (Connection connection = DriverManager.getConnection(SERVER_URL, "root", PASSWORD);
             Statement statement = connection.createStatement()) {
            for (String database : new String[]{EMPTY_DB, EXISTING_DB, SEED_DB}) {
                statement.execute("DROP DATABASE IF EXISTS " + database);
                statement.execute("CREATE DATABASE " + database
                        + " DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            }
        }
    }

    @Test
    void emptyDatabaseRunsEveryMigrationAndMatchesTheEntities() throws SQLException {
        // Context startup already ran the migrations and the ddl-auto=validate check.
        assertThat(Arrays.stream(flyway.info().applied()).map(info -> info.getVersion().getVersion()))
                .containsExactly("1", "2");
        assertThat(hasStatusPaidAtIndex(EMPTY_DB_URL)).isTrue();
    }

    @Test
    void existingDatabaseWithoutHistoryIsBaselinedAndOnlyRunsLaterMigrations() throws SQLException {
        // Recreate the server's state: the V1 tables exist, but Flyway has never run there.
        Flyway.configure().dataSource(EXISTING_DB_URL, "root", PASSWORD).target("1").load().migrate();
        try (Connection connection = DriverManager.getConnection(EXISTING_DB_URL, "root", PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE flyway_schema_history");
        }

        MigrateResult result = Flyway.configure()
                .dataSource(EXISTING_DB_URL, "root", PASSWORD)
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .load()
                .migrate();

        assertThat(result.migrationsExecuted).isEqualTo(1);
        assertThat(result.migrations).extracting(migration -> migration.version).containsExactly("2");
        MigrationInfo[] applied = Flyway.configure().dataSource(EXISTING_DB_URL, "root", PASSWORD).load()
                .info().applied();
        assertThat(applied).extracting(info -> info.getType().name()).containsExactly("BASELINE", "SQL");
        assertThat(hasStatusPaidAtIndex(EXISTING_DB_URL)).isTrue();
    }

    @Test
    void localSeedLoadsDemoDataWithDatesRelativeToToday() throws SQLException {
        // docker-compose.yml adds db/seed to the Flyway locations; the server only uses db/migration.
        Flyway.configure().dataSource(SEED_DB_URL, "root", PASSWORD)
                .locations("classpath:db/migration", "classpath:db/seed").load().migrate();

        try (Connection connection = DriverManager.getConnection(SEED_DB_URL, "root", PASSWORD);
             Statement statement = connection.createStatement()) {
            assertThat(count(statement, "SELECT COUNT(*) FROM events")).isEqualTo(96);
            assertThat(count(statement, "SELECT COUNT(*) FROM tickets")).isEqualTo(315);
            // Some performances are still ahead, and the ticket label shows the shifted date.
            assertThat(count(statement, "SELECT COUNT(*) FROM tickets WHERE start_time > NOW() AND booking_endtime > NOW()"))
                    .isPositive();
            assertThat(count(statement, "SELECT COUNT(*) FROM tickets WHERE type NOT LIKE CONCAT('[', DATE_FORMAT(start_time, '%c/%e'), ' %')"))
                    .isZero();

            var encoder = org.springframework.security.crypto.factory.PasswordEncoderFactories.createDelegatingPasswordEncoder();
            try (ResultSet demo = statement.executeQuery("SELECT email, role, password FROM users ORDER BY id")) {
                assertThat(demo.next()).isTrue();
                assertThat(demo.getString("email")).isEqualTo("user@example.com");
                assertThat(demo.getString("role")).isEqualTo("USER");
                assertThat(encoder.matches("password123!", demo.getString("password"))).isTrue();
                assertThat(demo.next()).isTrue();
                assertThat(demo.getString("email")).isEqualTo("admin@example.com");
                assertThat(demo.getString("role")).isEqualTo("ADMIN");
                assertThat(encoder.matches("password123!", demo.getString("password"))).isTrue();
            }
        }
    }

    private static long count(Statement statement, String sql) throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static boolean hasStatusPaidAtIndex(String url) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "root", PASSWORD);
             ResultSet indexes = connection.getMetaData().getIndexInfo(connection.getCatalog(), null, "orders", false, false)) {
            while (indexes.next()) {
                if (STATUS_PAID_AT_INDEX.equals(indexes.getString("INDEX_NAME"))) {
                    return true;
                }
            }
            return false;
        }
    }
}
