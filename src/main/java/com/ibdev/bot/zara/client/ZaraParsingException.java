package com.ibdev.bot.zara.client;

/**
 * The Zara page loaded but its structure was not recognized — almost certainly
 * the CSS selectors or the markup changed. Thrown instead of silently answering
 * "everything is out of stock", so that a parsing breakage stays visible rather
 * than masquerading as an unavailable product.
 *
 * @author i.bogatskii
 */
public class ZaraParsingException extends RuntimeException {

    public ZaraParsingException(final String message) {
        super(message);
    }

    public ZaraParsingException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
