package com.orangehrm.base;

import com.orangehrm.config.ConfigReader;
import com.orangehrm.enums.ConfigKey;
import io.qameta.allure.Step;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.openqa.selenium.NotFoundException;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Superclass for every page object. Owns the wait strategy.
 *
 * WHY NO @FindBy / PageFactory:
 * PageFactory looks tidy in tutorials and causes two real problems.
 *
 *   1. @FindBy fields are static declarations, so they cannot take a
 *      parameter. The moment you need By.xpath("//td[text()='" + name + "']")
 *      - which a table-driven app like OrangeHRM needs constantly - you drop
 *      out of the pattern and the class ends up half one style, half another.
 *
 *   2. AjaxElementLocatorFactory, the usual way to add waiting to PageFactory,
 *      re-finds elements on a hidden timer that interacts badly with explicit
 *      waits in exactly the way implicit waits do.
 *
 * Plain `By` constants plus explicit waits are slightly more typing and
 * entirely predictable. Predictable wins.
 */
public abstract class BasePage {

    private static final Logger LOG = LogManager.getLogger(BasePage.class);

    /**
     * Short timeout for negative checks. See isDisplayed() for why this is
     * separate from the main wait.
     */
    private static final Duration SHORT_TIMEOUT = Duration.ofSeconds(3);

    protected final WebDriver driver;
    protected final WebDriverWait wait;
    private final WebDriverWait shortWait;

    /**
     * WHY THE DRIVER IS A CONSTRUCTOR PARAMETER RATHER THAN DriverManager.getDriver():
     * A page object that reaches into a global has a hidden dependency - you
     * cannot tell from its signature that it needs a browser, and you cannot
     * construct one in isolation. Passing it in makes the dependency part of
     * the type. BaseTest supplies it via driver(), so tests never handle a
     * ThreadLocal directly.
     */
    protected BasePage(WebDriver driver) {
        this.driver = Objects.requireNonNull(driver, "WebDriver must not be null");

        int timeout = ConfigReader.getInt(ConfigKey.EXPLICIT_WAIT);
        this.wait = new WebDriverWait(driver, Duration.ofSeconds(timeout));
        this.shortWait = new WebDriverWait(driver, SHORT_TIMEOUT);

        // Separate statements, not chained into the assignment above:
        // ignoring() returns FluentWait<WebDriver>, so chaining it would not
        // typecheck against a WebDriverWait field. As its own statement it
        // works, because it mutates the instance and returns this.
        //
        // BOTH exceptions must be listed in one call. ignoring() REPLACES the
        // ignored set, and WebDriverWait's constructor already set it to
        // NotFoundException. Passing only StaleElementReferenceException would
        // stop NoSuchElementException being tolerated, and every wait would
        // fail on its first poll.
        //
        // Stale matters for OrangeHRM specifically: it is a Vue app, so a
        // component can re-render between the find and the click. Ignoring
        // stale means the wait re-finds instead of failing.
        this.wait.ignoring(NotFoundException.class, StaleElementReferenceException.class);
        this.shortWait.ignoring(NotFoundException.class, StaleElementReferenceException.class);
    }

    // -------------------------------------------------------------------------
    // Waits
    // -------------------------------------------------------------------------

    protected WebElement waitForVisible(By locator) {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(locator));
    }

    protected WebElement waitForClickable(By locator) {
        return wait.until(ExpectedConditions.elementToBeClickable(locator));
    }

    protected List<WebElement> waitForAllVisible(By locator) {
        return wait.until(ExpectedConditions.visibilityOfAllElementsLocatedBy(locator));
    }

    protected void waitForInvisible(By locator) {
        wait.until(ExpectedConditions.invisibilityOfElementLocated(locator));
    }

    protected void waitForUrlContains(String fragment) {
        wait.until(ExpectedConditions.urlContains(fragment));
    }

    // -------------------------------------------------------------------------
    // Interactions
    // -------------------------------------------------------------------------

    /*
     * WHY elementToBeClickable AND NOT presenceOfElementLocated:
     * Presence only means the node exists in the DOM. It can be hidden behind
     * a modal, still animating in, or disabled. Clicking it throws
     * ElementNotInteractableException or, worse, silently hits the overlay in
     * front of it. elementToBeClickable requires visible AND enabled.
     */
    @Step("Click: {locator}")
    protected void click(By locator) {
        LOG.debug("Clicking {}", locator);
        waitForClickable(locator).click();
    }

    @Step("Type \"{text}\" into {locator}")
    protected void type(By locator, String text) {
        WebElement element = waitForVisible(locator);
        element.clear();
        element.sendKeys(text);
    }

    @Step("Read text from {locator}")
    protected String getText(By locator) {
        return waitForVisible(locator).getText().trim();
    }

    protected String getValue(By locator) {
        // getDomProperty, not the deprecated getAttribute. For an input that
        // the user has typed into, the "value" attribute holds the original
        // markup value while the DOM property holds the current one - so
        // getAttribute returns stale data on a form the test just filled in.
        return waitForVisible(locator).getDomProperty("value");
    }

    /**
     * Returns whether an element is visible, WITHOUT throwing.
     *
     * WHY A SEPARATE SHORT TIMEOUT:
     * Use the main 15 second wait here and every negative assertion costs 15
     * seconds. A suite with forty "error message should not appear" checks
     * pays ten minutes to confirm that nothing happened. Three seconds is
     * ample to decide an element did not render.
     */
    protected boolean isDisplayed(By locator) {
        try {
            shortWait.until(ExpectedConditions.visibilityOfElementLocated(locator));
            return true;
        } catch (TimeoutException e) {
            return false;
        }
    }

    /**
     * Scrolls an element to the middle of the viewport.
     *
     * Needed because Selenium's auto-scroll puts the element at the very edge,
     * where OrangeHRM's sticky header can sit on top of it - producing an
     * ElementClickInterceptedException that looks like a locator problem.
     *
     * Deliberately NOT a jsClick() helper. A JavaScript click bypasses
     * visibility and overlay checks entirely, so it turns a real bug (a modal
     * is covering the button, as it would for a user) into a passing test.
     * Scroll, then click normally.
     */
    protected void scrollIntoView(By locator) {
        WebElement element = wait.until(ExpectedConditions.presenceOfElementLocated(locator));
        ((JavascriptExecutor) driver).executeScript(
                "arguments[0].scrollIntoView({block: 'center', behavior: 'instant'});", element);
    }

    // -------------------------------------------------------------------------
    // Page state
    // -------------------------------------------------------------------------

    public String getPageTitle() {
        return driver.getTitle();
    }

    public String getCurrentUrl() {
        return driver.getCurrentUrl();
    }
}
