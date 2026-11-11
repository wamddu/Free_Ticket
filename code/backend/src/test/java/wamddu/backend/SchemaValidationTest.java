package wamddu.backend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Deployments create the schema only from backup.sql and start with ddl-auto=validate.
 * This boots against that schema so an entity change without a matching backup.sql change fails here
 * instead of on the server. Needs MySQL on port 3308 with backup.sql loaded (CI does this).
 */
@EnabledIfEnvironmentVariable(named = "RUN_MYSQL_CONCURRENCY", matches = "true")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3308/free_ticket?serverTimezone=Asia/Seoul",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.username=root",
        "spring.datasource.password=concurrency-test",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.show-sql=false"
})
@DirtiesContext
class SchemaValidationTest {
    @Test
    void backupSqlMatchesEntities() {
        // Context startup performs the validation.
    }
}
