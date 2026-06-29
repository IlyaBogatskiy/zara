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

    /**
     * Last scraped price in minor units (e.g. cents) — the monitor's price-change baseline.
     */
    @Column(name = "last_price_amount")
    private Long lastPriceAmount;

    @Column(name = "last_price_currency")
    private String lastPriceCurrency;

    /**
     * Fraction digits of last price amount (2 for EUR, 0 for RSD).
     */
    @Column(name = "last_price_fraction_digits")
    private Integer lastPriceFractionDigits;

    public Product(final String productKey, final String name, final String link) {
        this.productKey = productKey;
        this.name = name;
        this.link = link;
    }
}
