package dev.kauzes.mizan.notification;

import dev.kauzes.mizan.common.identity.Permission;
import dev.kauzes.mizan.common.web.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the platform has decided a merchant should be told.
 *
 * <p>Reading only, and there is no endpoint here that creates one. A notification is a
 * consequence of something that happened to a payment, decided by the handler that read the
 * event; letting a caller write one would let a merchant tell themselves they had been paid.
 *
 * <p>Delivering these — signed, retried, dead lettered — is Epic 8. It will read this table
 * rather than the topic, because by then the decision has been made once and should not be
 * made again by whatever happens to redeliver.
 */
@RestController
@RequestMapping(
        path = "/api/v1/merchants/{merchantId}/notifications",
        produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Notifications", description = "What a merchant is to be told, and about what")
public class NotificationController {

    private final JdbcTemplate jdbc;

    public NotificationController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    @RequiresPermission(Permission.NOTIFICATION_READ)
    @Operation(
            summary = "List a merchant's notifications",
            description = "Most recent first. Scoped to the merchant in the path.")
    @ApiResponse(responseCode = "200", description = "The merchant's notifications")
    public List<NotificationResponse> list(@PathVariable UUID merchantId) {
        return jdbc.query(
                "select id, payment_id, kind, message, created_at from notification "
                        + "where merchant_id = ? order by created_at desc limit 200",
                (row, index) -> new NotificationResponse(
                        row.getObject("id", UUID.class),
                        row.getObject("payment_id", UUID.class),
                        row.getString("kind"),
                        row.getString("message"),
                        row.getTimestamp("created_at").toInstant()),
                merchantId);
    }

    @Schema(description = "Something the merchant should be told")
    public record NotificationResponse(
            UUID id,
            UUID paymentId,
            @Schema(example = "PAYMENT_CAPTURED") String kind,
            @Schema(example = "You have been paid 1250.00 TRY for order-77.") String message,
            Instant createdAt) {
    }
}
