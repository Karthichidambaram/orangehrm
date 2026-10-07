package com.orangehrm.config;

import com.orangehrm.enums.ConfigKey;
import com.orangehrm.exceptions.FrameworkException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Single source of configuration, with a defined precedence order.
 *
 * PRECEDENCE (highest wins):
 *   1. System properties           -Dbrowser=firefox
 *   2. config-{env}.properties     environment overlay
 *   3. config.properties           defaults
 *
 * That ordering is what lets one build definition serve local debugging and CI
 * with no file edits and no secrets committed. CI passes -D flags; you edit
 * nothing, and nothing environment-specific ends up in git history.
 */
public final class ConfigReader {

    private static final Logger LOG = LogManager.getLogger(ConfigReader.class);
    private static final Properties PROPERTIES = new Properties();
    private static final String BASE_FILE = "config/config.properties";

    /*
     * WHY A STATIC INITIALIZER RATHER THAN LAZY LOADING:
     *
     *  - Fail fast. A missing config file blows up the moment the class is
     *    touched, with a clear message, instead of surfacing later as a null
     *    browser name somewhere inside driver creation.
     *
     *  - Thread safety for free. The JVM guarantees class initialization runs
     *    exactly once, and that other threads block until it completes (JLS
     *    12.4.2). No synchronized block, no double-checked locking, no
     *    volatile field. A lazily-initialised Properties read from six
     *    parallel threads is a genuine race condition; this cannot be.
     */
    static {
        loadRequired(BASE_FILE);

        // Resolved AFTER the base file loads, so 'env' can come from either
        // the file or -Denv. The overlay is then layered on top of the base.
        String env = resolve(ConfigKey.ENV.key(), "qa");
        loadOptional("config/config-" + env + ".properties");

        LOG.info("Configuration loaded for env '{}'", env);
    }

    private ConfigReader() {
        // Throwing, not just private: this also defeats reflective
        // instantiation, which a bare private constructor does not.
        throw new AssertionError("ConfigReader is a static utility and must not be instantiated");
    }

    /**
     * Returns the value for a key, or throws with an actionable message.
     *
     * WHY THROW INSTEAD OF RETURNING null OR A SILENT DEFAULT:
     * A silent default is the worst of the three - the suite runs against the
     * wrong environment and reports green, which is worse than no result at
     * all. A null produces a NullPointerException far from the actual mistake.
     * Throwing here names the missing key and says how to supply it.
     */
    public static String get(ConfigKey key) {
        String value = resolve(key.key(), null);
        if (value == null || value.isBlank()) {
            throw new FrameworkException(
                    "Missing configuration key '" + key.key() + "'. "
                            + "Add it to " + BASE_FILE + " or pass -D" + key.key() + "=<value>.");
        }
        return value;
    }

    public static int getInt(ConfigKey key) {
        String raw = get(key);
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new FrameworkException(
                    "Config key '" + key.key() + "' must be a whole number but was '" + raw + "'", e);
        }
    }

    /**
     * WHY NOT Boolean.parseBoolean DIRECTLY: it treats anything that is not
     * "true" as false. So headless=yes silently means headless=false, and you
     * spend twenty minutes wondering why CI opened a real browser. Validated
     * explicitly so a typo is an error rather than a behaviour change.
     */
    public static boolean getBoolean(ConfigKey key) {
        String raw = get(key).toLowerCase();
        if (raw.equals("true")) {
            return true;
        }
        if (raw.equals("false")) {
            return false;
        }
        throw new FrameworkException(
                "Config key '" + key.key() + "' must be true or false but was '" + raw + "'");
    }

    private static String resolve(String key, String fallback) {
        String fromSystem = System.getProperty(key);
        if (fromSystem != null && !fromSystem.isBlank()) {
            return fromSystem.trim();
        }
        String fromFile = PROPERTIES.getProperty(key);
        // .trim() is not optional here. A trailing space in a properties file
        // is invisible in an editor and yields "chrome ", which fails enum
        // parsing with a message that looks identical to the correct value.
        return fromFile != null ? fromFile.trim() : fallback;
    }

    private static void loadRequired(String resource) {
        if (!load(resource)) {
            throw new FrameworkException(
                    "Required config file not found on the classpath: " + resource
                            + ". Expected it at src/main/resources/" + resource);
        }
    }

    private static void loadOptional(String resource) {
        if (!load(resource)) {
            LOG.debug("No overlay file '{}' - using base config only", resource);
        }
    }

    /*
     * WHY getResourceAsStream AND NOT new FileInputStream("src/main/resources/..."):
     * A filesystem path depends on the process working directory, which differs
     * between `mvn test`, a VS Code test run, and a packaged jar. Classpath
     * loading behaves identically in all three.
     */
    private static boolean load(String resource) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) {
                return false;
            }
            PROPERTIES.load(in);
            LOG.debug("Loaded config file '{}'", resource);
            return true;
        } catch (IOException e) {
            throw new FrameworkException("Failed reading config file: " + resource, e);
        }
    }
}
