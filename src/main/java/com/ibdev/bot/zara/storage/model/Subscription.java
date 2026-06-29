package com.ibdev.bot.zara.storage.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * @author i.bogatskii
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(
        name = "subscriptions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_sub_chat_product_size",
                columnNames = {"chat_id", "product_key", "size_label"}
        ),
        indexes = {
                @Index(name = "idx_sub_product_key", columnList = "product_key"),
                @Index(name = "idx_sub_chat_id", columnList = "chat_id")
        }
)
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @Column(name = "product_key", nullable = false)
    private String productKey;

    /**
     * Raw normalized size string ("S", "40", "*") — no enum mapping, no data loss.
     */
    @Column(name = "size_label", nullable = false)
    private String sizeLabel;

    /**
     * What this row is waiting for: a restock, or (after the user opted in) price/sell-out changes.
     * Nullable on purpose — a NOT NULL column can't be added to an already-populated table by
     * "ddl-auto: update" legacy rows read back as null and are treated as AWAIT_RESTOCK.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "mode")
    private SubscriptionMode mode = SubscriptionMode.AWAIT_RESTOCK;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_known_in_stock")
    private Boolean lastKnownInStock;

    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "close_reason")
    private SubscriptionChangeReason closeReason;

    public Subscription(final Long chatId, final String productKey, final String sizeLabel) {
        this.chatId = chatId;
        this.productKey = productKey;
        this.sizeLabel = sizeLabel;
        this.createdAt = Instant.now();
        this.lastKnownInStock = false;
    }

    public boolean isActive() {
        return this.closedAt == null;
    }

    public void close(final SubscriptionChangeReason reason) {
        this.closedAt = Instant.now();
        this.closeReason = reason;
    }

    public void reopen() {
        this.closedAt = null;
        this.closeReason = null;
        this.mode = SubscriptionMode.AWAIT_RESTOCK;
        this.lastKnownInStock = false;
        this.lastCheckedAt = null;
    }
}
