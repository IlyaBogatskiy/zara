package com.ibdev.bot.zara.client;

import com.ibdev.bot.zara.config.ZaraProperties;
import lombok.RequiredArgsConstructor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.stereotype.Component;

import java.net.MalformedURLException;
import java.net.URI;
import java.time.Duration;

/**
 * Creates WebDriver instances: a local ChromeDriver by default, or a RemoteWebDriver
 * when zara.driver.remote-url points to a Selenium Grid / standalone-chrome container
 * (the Docker Compose setup).
 *
 * @author i.bogatskii
 */
@Component
@RequiredArgsConstructor
public class WebDriverFactory {

    private final ChromeOptions chromeOptions;
    private final ZaraProperties properties;

    public WebDriver create() {
        final var driver = newDriver();
        configureTimeouts(driver);
        return driver;
    }

    private WebDriver newDriver() {
        final var remoteUrl = properties.getDriver().getRemoteUrl();
        if (remoteUrl == null || remoteUrl.isBlank()) {
            return new ChromeDriver(chromeOptions);
        }

        try {
            return new RemoteWebDriver(URI.create(remoteUrl).toURL(), chromeOptions);
        } catch (final MalformedURLException e) {
            throw new IllegalStateException("Invalid zara.driver.remote-url: " + remoteUrl, e);
        }
    }

    /**
     * Caps a single {@code driver.get} so a hung scrape (challenge page / stalled network) fails fast
     * instead of blocking the single-threaded scheduler for the driver's ~300 s default. A non-positive
     * {@code page-load-timeout-seconds} leaves the driver default untouched.
     */
    void configureTimeouts(final WebDriver driver) {
        final var seconds = properties.getDriver().getPageLoadTimeoutSeconds();
        if (seconds <= 0) {
            return;
        }
        driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(seconds));
    }

    public WebDriverWait createWait(final WebDriver driver) {
        return new WebDriverWait(driver, Duration.ofSeconds(properties.getDriver().getWaitSeconds()));
    }
}
