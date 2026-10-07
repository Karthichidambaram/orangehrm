package com.orangehrm.base;

import com.orangehrm.config.ConfigReader;
import com.orangehrm.driver.DriverFactory;
import com.orangehrm.driver.DriverManager;
import com.orangehrm.enums.ConfigKey;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openqa.selenium.WebDriver;
import org.testng.ITestResult;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;

/**
 * Superclass for every test class. Owns the browser lifecycle.
 *
 * ============================================================================
 * THE MOST IMPORTANT DESIGN DECISION IN THIS FILE: THERE IS NO `driver` FIELD.
 * ============================================================================
 *
 * The obvious implementation is:
 *
 *     protected WebDriver driver;              // <-- BROKEN
 *
 *     @BeforeMethod
 *     public void setUp() { driver = DriverFactory.createDriver(); }
 *
 * This is in a great many tutorials and it is unsafe, for a reason that is not
 * obvious: TestNG creates ONE instance of a test class and runs all of its
 * @Test methods against that single instance. With parallel="methods", those
 * methods run on different threads but share the instance - so an instance
 * field is shared mutable state across threads, exactly the bug ThreadLocal
 * was introduced to avoid. The ThreadLocal in DriverManager would be perfectly
 * correct and this field would quietly undo it.
 *
 * The fix is to not hold the driver anywhere that outlives a single method
 * call. driver() reads from the ThreadLocal on demand, so it always returns
 * the browser belonging to the calling thread, and there is no field to race
 * over. Page objects still receive the driver explicitly - the value just
 * comes from the right place.
 */
public abstract class BaseTest {

    private static final Logger LOG = LogManager.getLogger(BaseTest.class);

    /**
     * The driver for the current thread.
     *
     * Cheap: a ThreadLocal.get() is a lookup in the current thread's own map,
     * no locking and no contention. Calling this five times in a test method
     * costs nothing worth optimising.
     */
    protected WebDriver driver() {
        return DriverManager.getDriver();
    }

    /**
     * WHY @BeforeMethod AND NOT @BeforeClass - a fresh browser per test:
     *
     * @BeforeClass is faster; one browser launch serves twenty tests. It is
     * also how suites become unmaintainable. Test 3 leaves a modal open, or is
     * logged in as a user test 4 did not expect, and tests 4 through 20 fail
     * for reasons unrelated to what they assert. You then debug test 17 for an
     * hour when the bug is in test 3. The failures also move when you reorder
     * or filter tests, so they are not reproducible in isolation.
     *
     * A browser launch is ~2 seconds. One misdiagnosed cascade costs an hour.
     * Isolation is worth paying for, and it is also a precondition for
     * parallel="methods" - a shared browser cannot be driven by four threads.
     *
     * WHY alwaysRun = true:
     * Without it, setup and teardown are skipped when a test is skipped due to
     * a group filter or a failed dependsOnMethods. Skipped teardown means a
     * leaked browser process, and leaked processes accumulate until the CI
     * runner exhausts memory.
     *
     * The @Parameters/@Optional pair lets a TestNG XML <test> block override
     * the browser per block - which is how cross-browser.xml runs Chrome and
     * Firefox in parallel. Omit it and the -Dbrowser value applies to all.
     */
    @BeforeMethod(alwaysRun = true)
    @Parameters({"browser"})
    public void setUp(@Optional String browserFromXml, ITestResult result) {
        if (browserFromXml != null && !browserFromXml.isBlank()) {
            // Written into system properties so ConfigReader's normal
            // precedence rules apply - the XML value is treated exactly like
            // a -D flag rather than needing a separate code path.
            System.setProperty(ConfigKey.BROWSER.key(), browserFromXml);
        }

        WebDriver driver = DriverFactory.createDriver();

        // Bound to the ThreadLocal immediately, BEFORE anything can fail.
        // Listeners take screenshots from DriverManager, so if the navigation
        // below throws, the listener still has a browser to photograph.
        DriverManager.setDriver(driver);

        driver.get(ConfigReader.get(ConfigKey.BASE_URL));

        LOG.info("Starting '{}' on thread '{}'",
                result.getMethod().getMethodName(), Thread.currentThread().getName());
    }

    /**
     * WHY TEARDOWN DELEGATES TO DriverManager.quitDriver() RATHER THAN
     * CALLING driver.quit() HERE:
     * quit() alone leaves the ThreadLocal entry behind - the classic leak.
     * Putting quit-and-remove together in one method means the remove can
     * never be forgotten, including by a future subclass that overrides this.
     */
    @AfterMethod(alwaysRun = true)
    public void tearDown(ITestResult result) {
        LOG.info("Finished '{}' with status {}",
                result.getMethod().getMethodName(), statusOf(result));
        DriverManager.quitDriver();
    }

    private static String statusOf(ITestResult result) {
        return switch (result.getStatus()) {
            case ITestResult.SUCCESS -> "PASS";
            case ITestResult.FAILURE -> "FAIL";
            case ITestResult.SKIP -> "SKIP";
            default -> "UNKNOWN";
        };
    }
}
