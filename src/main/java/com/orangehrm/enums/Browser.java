package com.orangehrm.enums;

import com.orangehrm.exceptions.FrameworkException;

import java.util.Arrays;
import java.util.Locale;

/**
 * Supported browsers.
 *
 * WHY NOT JUST PASS THE STRING AROUND:
 * A String reaches DriverFactory's switch and falls through to a default
 * branch. An enum makes the switch exhaustive - add a constant here and the
 * compiler points at every switch that has not handled it yet. That is how you
 * add a browser without discovering a missing branch in CI.
 */
public enum Browser {

    CHROME,
    FIREFOX,
    EDGE;

    /**
     * Parses a config value into an enum constant.
     *
     * Case and whitespace insensitive, because "Chrome", " chrome" and "CHROME"
     * all arrive in practice - from properties files, -D flags and TestNG XML
     * parameters respectively.
     */
    public static Browser from(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new FrameworkException(
                    "No browser specified. Set 'browser' in config.properties or pass -Dbrowser=<name>. "
                            + "Supported: " + Arrays.toString(values()));
        }
        try {
            // Locale.ROOT, not the default locale. In a Turkish locale
            // "i".toUpperCase() produces a dotted capital I, and FIREFOX
            // stops matching. A real bug, and a genuinely baffling one.
            return Browser.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // Listing valid values in the message turns a 10 minute debugging
            // session into a 10 second fix.
            throw new FrameworkException(
                    "Unsupported browser '" + raw + "'. Supported: " + Arrays.toString(values()), e);
        }
    }
}
