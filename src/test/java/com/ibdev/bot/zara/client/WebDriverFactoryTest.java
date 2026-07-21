package com.ibdev.bot.zara.client;

import com.ibdev.bot.zara.config.ZaraProperties;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeOptions;

import java.time.Duration;

import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Guards the WebDriver page-load timeout wiring — the bound that keeps a hung Selenium scrape from
 * blocking the single-threaded scheduler (the observed 50 s tick). The hang itself is infra, but
 * "the factory actually applies the configured cap" is cheap to pin down.
 *
 * @author i.bogatskii
 */
class WebDriverFactoryTest {

    private WebDriverFactory factory(final int pageLoadTimeoutSeconds) {
        final var props = new ZaraProperties();
        props.getDriver().setPageLoadTimeoutSeconds(pageLoadTimeoutSeconds);
        return new WebDriverFactory(new ChromeOptions(), props);
    }

    @Test
    void appliesConfiguredPageLoadTimeout() {
        final var driver = mock(WebDriver.class, RETURNS_DEEP_STUBS);

        factory(20).configureTimeouts(driver);

        verify(driver.manage().timeouts()).pageLoadTimeout(Duration.ofSeconds(20));
    }

    @Test
    void leavesDriverDefaultWhenTimeoutNonPositive() {
        final var driver = mock(WebDriver.class, RETURNS_DEEP_STUBS);

        factory(0).configureTimeouts(driver);

        verify(driver, never()).manage();
    }
}
