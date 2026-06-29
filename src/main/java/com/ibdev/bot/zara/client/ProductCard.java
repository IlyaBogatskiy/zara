package com.ibdev.bot.zara.client;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
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

    /**
     * Current price, or null when the scrape source could not read it.
     */
    private PriceInfo price;
}
