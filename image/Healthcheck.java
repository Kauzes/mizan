import java.net.HttpURLConnection;
import java.net.URI;

/**
 * Asks a service whether it is ready, from inside an image that has nothing else to ask with.
 *
 * <p>The runtime image is distroless: no shell, no package manager, and no curl. Every one of
 * those is something a compromised process could use and none is something the service needs
 * (MIZ-81). The one thing that did need curl was the container healthcheck, so this replaces
 * it with the one program the image already has — a JVM.
 *
 * <p>Compiled in the build stage and copied in as a single class, so the image gains a few
 * kilobytes rather than a binary. Deliberately the smallest thing that answers the question: an
 * HTTP GET, a short timeout, and an exit code. A healthcheck that can hang is a healthcheck that
 * reports a stuck service as starting forever.
 *
 * <p>Usage: {@code java -cp /healthcheck Healthcheck http://localhost:8083/actuator/health/readiness}
 */
public final class Healthcheck {

    private static final int TIMEOUT_MILLIS = 3_000;

    private Healthcheck() {
    }

    public static void main(String[] arguments) {
        if (arguments.length != 1) {
            System.err.println("usage: Healthcheck <url>");
            System.exit(2);
        }
        try {
            HttpURLConnection connection =
                    (HttpURLConnection) URI.create(arguments[0]).toURL().openConnection();
            connection.setConnectTimeout(TIMEOUT_MILLIS);
            connection.setReadTimeout(TIMEOUT_MILLIS);
            connection.setRequestMethod("GET");
            int status = connection.getResponseCode();
            connection.disconnect();
            // Ready is a 2xx and nothing else. Readiness answers 503 while a dependency is
            // unreachable, and that is precisely the answer that must fail this check.
            System.exit(status >= 200 && status < 300 ? 0 : 1);
        } catch (Exception unreachable) {
            System.err.println(unreachable.getClass().getSimpleName() + ": " + unreachable.getMessage());
            System.exit(1);
        }
    }
}
