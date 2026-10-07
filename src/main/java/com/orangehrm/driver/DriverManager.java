package com.orangehrm.driver;

import com.orangehrm.exceptions.FrameworkException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openqa.selenium.WebDriver;

/**
 * Holds one WebDriver per thread.
 *
 * This class is the single most important one in the framework, and it is
 * twenty lines long. Everything about parallel execution comes down to the
 * field below being a ThreadLocal and not a static WebDriver.
 *
 * WHAT GOES WRONG WITH `static WebDriver driver`:
 * With parallel="methods" and threadCount=4, four threads run @BeforeMethod at
 * roughly the same time. Each assigns to the same static field. The last write
 * wins, so all four tests drive the SAME browser while three orphaned Chrome
 * processes sit idle. The symptom is surreal: tests fail with "element not
 * found" on pages they never navigated to, and the failures move around
 * between runs. People lose days to this.
 *
 * ThreadLocal gives each thread its own slot in its own ThreadLocalMap. No
 * locking, no contention, no sharing.
 */
public final class DriverManager {

    private static final Logger LOG = LogManager.getLogger(DriverManager.class);

    private static final ThreadLocal<WebDriver> DRIVER = new ThreadLocal<>();

    private DriverManager() {
        throw new AssertionError("DriverManager is a static utility and must not be instantiated");
    }

    public static void setDriver(WebDriver driver) {
        if (driver == null) {
            // Storing null would turn getDriver()'s helpful error into a
            // misleading one, so refuse at the door.
            throw new FrameworkException("Refusing to bind a null WebDriver to the current thread");
        }
        DRIVER.set(driver);
        LOG.debug("Driver bound to thread '{}'", Thread.currentThread().getName());
    }

    /**
     * WHY THIS THROWS RATHER THAN RETURNING null:
     * A null driver produces a NullPointerException inside whichever page
     * object happened to touch it first - a stack trace that points at
     * LoginPage.enterUsername() when the real mistake was a test class that
     * forgot to extend BaseTest. The message below names both likely causes.
     */
    public static WebDriver getDriver() {
        WebDriver driver = DRIVER.get();
        if (driver == null) {
            throw new FrameworkException(
                    "No WebDriver is bound to thread '" + Thread.currentThread().getName() + "'. "
                            + "Either the test class does not extend BaseTest, or @BeforeMethod did not run "
                            + "(check for an exception thrown during setup).");
        }
        return driver;
    }

    /**
     * Lets listeners ask "is there a browser to screenshot?" without
     * triggering the exception above. A failure during driver creation is
     * exactly when the listener runs and exactly when there is no driver.
     */
    public static boolean hasDriver() {
        return DRIVER.get() != null;
    }

    /**
     * Quits the browser and clears the thread's slot.
     *
     * WHY DRIVER.remove() AND NOT DRIVER.set(null) - THE SUBTLE ONE:
     * TestNG runs tests on a reused thread pool. set(null) leaves a live entry
     * in that thread's ThreadLocalMap pointing at null. Two consequences:
     *
     *   1. Memory. Entries accumulate across hundreds of tests, and the
     *      ThreadLocalMap keys are weak references but the values are not -
     *      this is the classic ThreadLocal leak.
     *   2. Worse, it hides bugs. hasDriver() would need null-checking in two
     *      places, and a stale entry can outlive the test that created it.
     *
     * remove() deletes the entry outright. Always remove, never set(null).
     *
     * The finally block matters too: if driver.quit() throws - which it does
     * when the browser has already crashed - the slot must still be cleared,
     * or the next test on this thread inherits a dead driver.
     */
    public static void quitDriver() {
        WebDriver driver = DRIVER.get();
        if (driver == null) {
            return;
        }
        try {
            driver.quit();
            LOG.debug("Driver quit on thread '{}'", Thread.currentThread().getName());
        } catch (Exception e) {
            // Swallowed deliberately. A teardown failure must not mask the
            // actual test failure that is already on its way to the report.
            LOG.warn("driver.quit() failed on thread '{}': {}",
                    Thread.currentThread().getName(), e.getMessage());
        } finally {
            DRIVER.remove();
        }
    }
}
