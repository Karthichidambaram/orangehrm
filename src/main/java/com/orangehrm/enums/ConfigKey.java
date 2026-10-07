package com.orangehrm.enums;

/**
 * Every configuration key the framework understands.
 *
 * WHY AN ENUM INSTEAD OF STRING LITERALS:
 * ConfigReader.get("browswer") compiles fine and fails at runtime with a null,
 * ten frames deep inside DriverFactory, at 2am in CI. ConfigKey.BROWSWER does
 * not compile. The enum turns a whole category of runtime mystery into an
 * immediate red squiggle, and it doubles as the authoritative list of what is
 * configurable - no grepping the codebase to find out.
 */
public enum ConfigKey {

    ENV("env"),
    BASE_URL("baseUrl"),

    BROWSER("browser"),
    HEADLESS("headless"),

    RUN_MODE("runMode"),
    GRID_URL("gridUrl"),

    EXPLICIT_WAIT("explicitWait"),
    PAGE_LOAD_TIMEOUT("pageLoadTimeout"),

    RETRY_COUNT("retryCount"),
    SCREENSHOT_ON_FAILURE("screenshotOnFailure"),
    SCREENSHOT_ON_PASS("screenshotOnPass"),

    ADMIN_USERNAME("admin.username"),
    ADMIN_PASSWORD("admin.password");

    private final String key;

    ConfigKey(String key) {
        this.key = key;
    }

    /** The literal key as it appears in config.properties and in -D flags. */
    public String key() {
        return key;
    }
}
