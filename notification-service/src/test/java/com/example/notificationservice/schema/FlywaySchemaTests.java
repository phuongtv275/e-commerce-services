package com.example.notificationservice.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@org.junit.jupiter.api.condition.EnabledIf("isPostgresAvailable")
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=${NOTIFICATION_TEST_DB_URL:jdbc:postgresql://localhost:5432/notification_db}",
        "spring.datasource.username=postgres",
        "spring.datasource.password=postgres",
        "spring.datasource.driver-class-name=org.postgresql.Driver"
})
class FlywaySchemaTests {

    static boolean isPostgresAvailable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(System.getenv().getOrDefault("NOTIFICATION_TEST_DB_HOST", "localhost"), 5432), 1000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Positive: Flyway migrations execute successfully and create outbox_events along with core tables")
    void testTablesExist() {
        Integer templatesCount = jdbcTemplate.queryForObject("SELECT count(*) FROM notification_templates", Integer.class);
        assertThat(templatesCount).isNotNull().isGreaterThanOrEqualTo(1);

        Integer logsCount = jdbcTemplate.queryForObject("SELECT count(*) FROM notification_logs", Integer.class);
        assertThat(logsCount).isNotNull();

        Integer processedCount = jdbcTemplate.queryForObject("SELECT count(*) FROM processed_events", Integer.class);
        assertThat(processedCount).isNotNull();

        Integer outboxCount = jdbcTemplate.queryForObject("SELECT count(*) FROM outbox_events", Integer.class);
        assertThat(outboxCount).isNotNull();
    }

    @Test
    @DisplayName("Positive: Insert valid outbox event into outbox_events table succeeds")
    void testOutboxEventInsert_Positive() {
        UUID eventId = UUID.randomUUID();
        int inserted = jdbcTemplate.update(
                "INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, event_version, payload, status, retry_count, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                eventId, "Notification", "notif-123", "NotificationSentEvent", 1, "{}", "PENDING", 0, Timestamp.from(Instant.now())
        );
        assertThat(inserted).isEqualTo(1);

        // Clean up
        jdbcTemplate.update("DELETE FROM outbox_events WHERE id = ?", eventId);
    }

    @Test
    @DisplayName("Negative: Insert into outbox_events with null required field fails DataIntegrityViolationException")
    void testOutboxEventMissingRequiredField_Negative() {
        UUID eventId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, event_version, payload, status, retry_count, created_at) " +
                        "VALUES (?, NULL, ?, ?, ?, ?, ?, ?, ?)",
                eventId, "notif-123", "NotificationSentEvent", 1, "{}", "PENDING", 0, Timestamp.from(Instant.now())
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Negative: Insert duplicate PK into outbox_events fails DataIntegrityViolationException")
    void testOutboxEventDuplicatePk_Negative() {
        UUID eventId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, event_version, payload, status, retry_count, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                eventId, "Notification", "notif-123", "NotificationSentEvent", 1, "{}", "PENDING", 0, Timestamp.from(Instant.now())
        );

        try {
            assertThatThrownBy(() -> jdbcTemplate.update(
                    "INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, event_version, payload, status, retry_count, created_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    eventId, "Notification", "notif-456", "NotificationSentEvent", 1, "{}", "PENDING", 0, Timestamp.from(Instant.now())
            )).isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbcTemplate.update("DELETE FROM outbox_events WHERE id = ?", eventId);
        }
    }
}
