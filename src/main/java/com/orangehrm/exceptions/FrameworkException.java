package com.orangehrm.exceptions;

/**
 * Thrown when the framework itself is misconfigured or misused - a missing
 * config key, an unknown browser, a driver requested on a thread that has none.
 *
 * WHY UNCHECKED (extends RuntimeException):
 * None of these conditions are recoverable at the call site. A page object
 * cannot meaningfully "handle" a missing baseUrl. Making it checked would force
 * try/catch blocks through every layer that add noise and catch nothing.
 *
 * WHY A DEDICATED TYPE RATHER THAN IllegalStateException:
 * It separates "the framework is broken" from "the application under test is
 * broken". When a FrameworkException shows up in an Allure report you know to
 * look at your own config, not at OrangeHRM. That distinction is worth a class.
 */
public class FrameworkException extends RuntimeException {

    public FrameworkException(String message) {
        super(message);
    }

    public FrameworkException(String message, Throwable cause) {
        super(message, cause);
    }
}
