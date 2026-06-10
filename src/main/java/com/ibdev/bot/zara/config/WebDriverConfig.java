package com.ibdev.bot.zara.config;

import org.openqa.selenium.chrome.ChromeOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author i.bogatskii
 */
@Configuration
public class WebDriverConfig {

    @Bean
    public ChromeOptions chromeOptions(final ZaraProperties properties) {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new");
        options.addArguments("--window-size=1920,1080");
        options.addArguments("--disable-gpu");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--blink-settings=imagesEnabled=false");
        options.addArguments("--user-agent=" + properties.getUserAgent());

        return options;
    }
}
