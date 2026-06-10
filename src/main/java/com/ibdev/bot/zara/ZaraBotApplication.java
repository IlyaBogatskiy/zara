package com.ibdev.bot.zara;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * @author i.bogatskii
 */
@EnableScheduling
@ConfigurationPropertiesScan
@SpringBootApplication
public class ZaraBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZaraBotApplication.class, args);
    }
}
