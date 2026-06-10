package com.ibdev.bot.zara.service.page;

import com.ibdev.bot.zara.client.ProductCard;

import java.util.Map;

/**
 * @author i.bogatskii
 */
public interface PageService {

    ProductCard loadProductCard(String link);

    Map<String, Boolean> checkProductSizesAvailability(String link);
}
