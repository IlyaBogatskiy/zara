package com.ibdev.bot.zara.storage.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Scraped facts about a product — one row per product, shared by all chats.
 * The size lineup is deliberately not stored here: it is always taken fresh.
 *
 * @author i.bogatskii
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "products")
public class Product {

    @Id
    @Column(name = "product_key")
    private String productKey;

    @Column(name = "name")
    private String name;

    @Column(name = "link", nullable = false, length = 2048)
    private String link;

    @Column(name = "last_scraped_at")
    private Instant lastScrapedAt;

    public Product(final String productKey, final String name, final String link) {
        this.productKey = productKey;
        this.name = name;
        this.link = link;
    }
}
