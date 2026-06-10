package com.ibdev.bot.zara.config;

import com.pengrad.telegrambot.TelegramBot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author i.bogatskii
 */
@Configuration
public class TelegramBotConfig {

    @Bean
    public TelegramBot telegramBot(@Value("${telegram.token}") final String token) {
        return new TelegramBot(token);
    }
}
