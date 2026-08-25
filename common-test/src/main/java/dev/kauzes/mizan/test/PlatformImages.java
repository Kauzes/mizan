package dev.kauzes.mizan.test;

/**
 * The image coordinates the build read out of .env, which is the same file Docker Compose
 * reads. Nothing here has a default: a missing value means the build did not pass it
 * through, and silently falling back to a hardcoded tag is how a harness ends up testing
 * against a different version than the one that gets deployed.
 */
public final class PlatformImages {

    private static final String PREFIX = "mizan.env.";

    private PlatformImages() {
    }

    public static String postgres() {
        return require("POSTGRES_IMAGE");
    }

    public static String kafka() {
        return require("KAFKA_IMAGE");
    }

    public static String redis() {
        return require("REDIS_IMAGE");
    }

    private static String require(String key) {
        String value = System.getProperty(PREFIX + key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "system property " + PREFIX + key + " is not set. The Gradle build reads "
                            + ".env and passes it to the test JVM; running a test outside Gradle "
                            + "needs -D" + PREFIX + key + "=<image>");
        }
        return value;
    }
}
