package com.orangehrm.driver;

import com.orangehrm.config.ConfigReader;
import com.orangehrm.enums.Browser;
import com.orangehrm.enums.ConfigKey;
import com.orangehrm.exceptions.FrameworkException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.edge.EdgeOptions;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.openqa.selenium.remote.AbstractDriverOptions;
import org.openqa.selenium.remote.RemoteWebDriver;

import java.net.MalformedURLException;
import java.net.URI;
import java.time.Duration;

/**
 * Builds WebDriver instances. Does not store them - that is DriverManager's job.
 *
 * WHY THESE ARE TWO CLASSES AND NOT ONE:
 * They change for entirely different reasons. This file changes whenever Chrome
 * deprecates a flag, a new browser is added, or the grid URL format shifts -
 * which is often. DriverManager changes approximately never. Keeping them
 * separate means the volatile code and the critical code are not in the same
 * file, and nothing outside this package needs to import the factory at all.
 *
 * NOTE: there is no WebDriverManager dependency. Selenium Manager has been
 * built into the Java bindings since 4.6 and resolves the matching driver
 * binary itself, caching it under ~/.cache/selenium.
 */
public final class DriverFactory {

    private static final Logger LOG = LogManager.getLogger(DriverFactory.class);

    private DriverFactory() {
        throw new AssertionError("DriverFactory is a static utility and must not be instantiated");
    }

    public static WebDriver createDriver() {
        Browser browser = Browser.from(ConfigReader.get(ConfigKey.BROWSER));
        boolean headless = ConfigReader.getBoolean(ConfigKey.HEADLESS);
        boolean remote = "remote".equalsIgnoreCase(ConfigReader.get(ConfigKey.RUN_MODE));

        LOG.info("Creating {} driver (headless={}, remote={}) on thread '{}'",
                browser, headless, remote, Thread.currentThread().getName());

        WebDriver driver = remote
                ? createRemote(browser, headless)
                : createLocal(browser, headless);

        applyTimeouts(driver, headless);
        return driver;
    }

    // -------------------------------------------------------------------------
    // Local
    // -------------------------------------------------------------------------

    /*
     * An arrow switch over an enum with no default branch. If someone adds
     * SAFARI to the Browser enum, this stops compiling and names this exact
     * line. A default branch that threw at runtime would have let it ship.
     */
    private static WebDriver createLocal(Browser browser, boolean headless) {
        return switch (browser) {
            case CHROME -> new ChromeDriver(chromeOptions(headless));
            case FIREFOX -> new FirefoxDriver(firefoxOptions(headless));
            case EDGE -> new EdgeDriver(edgeOptions(headless));
        };
    }

    // -------------------------------------------------------------------------
    // Remote (Selenium Grid / Docker)
    // -------------------------------------------------------------------------

    private static WebDriver createRemote(Browser browser, boolean headless) {
        AbstractDriverOptions<?> options = switch (browser) {
            case CHROME -> chromeOptions(headless);
            case FIREFOX -> firefoxOptions(headless);
            case EDGE -> edgeOptions(headless);
        };

        String gridUrl = ConfigReader.get(ConfigKey.GRID_URL);
        try {
            // URI.create(..).toURL() rather than new URL(String), which is
            // deprecated for removal as of Java 20.
            return new RemoteWebDriver(URI.create(gridUrl).toURL(), options);
        } catch (MalformedURLException | IllegalArgumentException e) {
            throw new FrameworkException(
                    "Invalid gridUrl '" + gridUrl + "'. Expected something like "
                            + "http://localhost:4444/wd/hub", e);
        }
    }

    // -------------------------------------------------------------------------
    // Options
    // -------------------------------------------------------------------------

    private static ChromeOptions chromeOptions(boolean headless) {
        ChromeOptions options = new ChromeOptions();

        if (headless) {
            // "--headless=new" is the real Chrome renderer. The old
            // "--headless" ran a separate, subtly different code path whose
            // rendering and event handling did not match headed Chrome - a
            // notorious source of "passes locally, fails in CI".
            options.addArguments("--headless=new");

            // NOT OPTIONAL, and the single biggest headless gotcha.
            // Headless Chrome defaults to an 800x600 viewport. OrangeHRM is
            // responsive: below roughly 1200px the left navigation collapses
            // into a hamburger menu. Every nav-dependent test then fails with
            // ElementNotInteractable while passing perfectly on your 1440p
            // monitor. Pin the viewport.
            options.addArguments("--window-size=1920,1080");

            // Container-specific. Docker gives /dev/shm only 64MB by default;
            // Chrome exhausts it and dies with an opaque "tab crashed".
            options.addArguments("--disable-dev-shm-usage");

            // Reduces sandbox privileges. Required in most CI containers,
            // deliberately NOT applied to headed local runs, where it would
            // weaken your own browser's security for no benefit.
            options.addArguments("--no-sandbox");
        }

        options.addArguments("--disable-notifications", "--disable-infobars", "--disable-extensions");
        options.setAcceptInsecureCerts(true);
        return options;
    }

    private static FirefoxOptions firefoxOptions(boolean headless) {
        FirefoxOptions options = new FirefoxOptions();
        if (headless) {
            // One dash, not two. Firefox's flag syntax differs from Chrome's,
            // and "--headless" is silently ignored rather than rejected - so
            // a real browser window opens in CI and nothing tells you why.
            options.addArguments("-headless");
            options.addArguments("--width=1920", "--height=1080");
        }
        options.setAcceptInsecureCerts(true);
        return options;
    }

    private static EdgeOptions edgeOptions(boolean headless) {
        EdgeOptions options = new EdgeOptions();
        if (headless) {
            // Edge is Chromium, so it takes Chrome's flags.
            options.addArguments("--headless=new");
            options.addArguments("--window-size=1920,1080");
            options.addArguments("--disable-dev-shm-usage", "--no-sandbox");
        }
        options.addArguments("--disable-notifications");
        options.setAcceptInsecureCerts(true);
        return options;
    }

    // -------------------------------------------------------------------------
    // Timeouts
    // -------------------------------------------------------------------------

    private static void applyTimeouts(WebDriver driver, boolean headless) {
        driver.manage().timeouts().pageLoadTimeout(
                Duration.ofSeconds(ConfigReader.getInt(ConfigKey.PAGE_LOAD_TIMEOUT)));

        /*
         * THERE IS NO IMPLICIT WAIT HERE, AND THAT IS THE POINT.
         *
         * Selenium's own documentation warns against combining implicit and
         * explicit waits: the resulting timeout is unpredictable, not the
         * maximum of the two. A WebDriverWait polling for invisibility while a
         * 10 second implicit wait is active can block for far longer than
         * either value, and the behaviour varies by driver implementation.
         *
         * It also makes negative assertions catastrophically slow. Checking
         * that an error message is ABSENT costs the full implicit wait every
         * time, so a suite with fifty such checks pays eight minutes for
         * nothing.
         *
         * All waiting in this framework is explicit, in BasePage. One
         * mechanism, one place to tune, predictable timing.
         */

        if (!headless) {
            // Pointless headless - there is no window to maximize - and on
            // some Linux CI images it throws outright.
            driver.manage().window().maximize();
        }
    }
}
