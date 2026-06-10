package com.ibdev.bot.zara.client;

import lombok.extern.log4j.Log4j2;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;

/**
 * Saves the page HTML and a screenshot when parsing breaks — otherwise figuring
 * out what exactly changed in Zara's markup is a blind exercise.
 * The dumps/ folder lives next to the application; old dumps are rotated.
 *
 * @author i.bogatskii
 */
@Log4j2
public final class PageDumper {

    private static final Path DUMP_DIR = Path.of("dumps");
    private static final int MAX_FILES = 40;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private PageDumper() {
    }

    public static void dump(final WebDriver driver, final String tag) {
        try {
            Files.createDirectories(DUMP_DIR);
            final var base = TS.format(LocalDateTime.now()) + "-" + tag;

            Files.writeString(DUMP_DIR.resolve(base + ".html"), driver.getPageSource());
            if (driver instanceof TakesScreenshot screenshot) {
                Files.write(DUMP_DIR.resolve(base + ".png"), screenshot.getScreenshotAs(OutputType.BYTES));
            }

            log.info("Page dump saved: dumps/{}.html", base);
            cleanupOld();
        } catch (final Exception e) {
            log.warn("Failed to dump page ({}): {}", tag, e.getMessage());
        }
    }

    private static void cleanupOld() throws IOException {
        try (var files = Files.list(DUMP_DIR)) {
            final var sorted = files
                    .sorted(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .toList();

            for (int i = 0; i < sorted.size() - MAX_FILES; i++) {
                Files.deleteIfExists(sorted.get(i));
            }
        }
    }
}
