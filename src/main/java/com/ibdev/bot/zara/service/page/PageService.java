package com.ibdev.bot.zara.service.page;

import com.ibdev.bot.zara.client.ProductCard;
import com.ibdev.bot.zara.client.ProductSnapshot;

/**
 * @author i.bogatskii
 */
public interface PageService {

    ProductCard loadProductCard(String link);

    ProductSnapshot checkProductSizesAvailability(String link);

    ProductSnapshot checkViaSelenium(String link);
}
