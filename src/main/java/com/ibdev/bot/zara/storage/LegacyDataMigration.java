package com.ibdev.bot.zara.storage;

import com.ibdev.bot.zara.client.ClothingSizes;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-shot migration from the legacy schema (product_details + size_details with
 * a subscription flag) to the normalized one (products + subscriptions). Runs only
 * when the new table is empty and the legacy tables exist. Leaves the old tables
 * intact — drop them manually after verification:
 *   drop table size_details; drop table product_details;
 *
 * @author i.bogatskii
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class LegacyDataMigration {

    private final JdbcTemplate jdbcTemplate;

    @Order(1)
    @Transactional
    @EventListener(ApplicationReadyEvent.class)
    public void migrate() {
        if (!legacyTablesExist()) {
            return;
        }

        final var existing = this.jdbcTemplate.queryForObject(
                "select count(*) from subscriptions", Long.class);
        if (existing != null && existing > 0) {
            log.info("Subscriptions table already populated, skipping legacy migration.");
            return;
        }

        this.jdbcTemplate.update("""
                insert into products (product_key, name, link, last_scraped_at)
                select product_key, max(name), max(link), now()
                from product_details
                group by product_key
                on conflict (product_key) do nothing
                """);

        final var rows = this.jdbcTemplate.queryForList("""
                select pd.chat_id, pd.product_key, sd.clothing_size, sd.size_availability
                from size_details sd
                join product_details pd on pd.id = sd.product_id
                where sd.subscription = true
                """);

        var migrated = 0;
        for (final var row : rows) {
            final String sizeLabel;
            try {
                sizeLabel = ClothingSizes.valueOf((String) row.get("clothing_size")).getSize();
            } catch (final IllegalArgumentException e) {
                log.warn("Unknown legacy size '{}' for product {}, skipping.",
                        row.get("clothing_size"), row.get("product_key"));
                continue;
            }

            this.jdbcTemplate.update("""
                            insert into subscriptions
                                (chat_id, product_key, size_label, created_at, last_known_in_stock)
                            values (?, ?, ?, now(), ?)
                            on conflict do nothing
                            """,
                    row.get("chat_id"),
                    row.get("product_key"),
                    sizeLabel,
                    row.get("size_availability")
            );
            migrated++;
        }

        log.info("Legacy migration done: {} subscription(s) migrated. " +
                "Legacy tables product_details/size_details left intact — drop them manually after verification.",
                migrated);
    }

    private boolean legacyTablesExist() {
        final var count = this.jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where table_name = 'product_details'",
                Long.class
        );
        return count != null && count > 0;
    }
}
