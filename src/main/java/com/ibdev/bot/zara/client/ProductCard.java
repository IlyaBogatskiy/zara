package com.ibdev.bot.zara.client;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Scraped product card — pure facts from the page, not tied to any chat.
 * By convention sizeDetails contains only the sizes that are OUT of stock.
 *
 * @author i.bogatskii
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProductCard {

    private String productKey;
    private String name;
    private String link;
    private List<SizeInfo> sizeDetails;
}
