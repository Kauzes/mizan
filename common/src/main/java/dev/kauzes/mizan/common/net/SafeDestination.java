package dev.kauzes.mizan.common.net;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Whether this platform is willing to make a request to a URL somebody else chose.
 *
 * <p>A webhook endpoint is a URL a merchant types in, and a service that will fetch any URL it
 * is given on request is a service that will make requests inside its own network on a
 * stranger's behalf. That is server-side request forgery, and the usual first victim is a cloud
 * metadata endpoint at 169.254.169.254 handing out credentials.
 *
 * <p>What is refused, and why each one:
 *
 * <ul>
 *   <li><b>Anything but https.</b> A webhook carries what a merchant was paid and is signed
 *       with a shared secret; sending it in clear text gives both away to anybody on the path.
 *   <li><b>Loopback</b> — the service itself, and anything else listening on this host.
 *   <li><b>Private ranges and link-local</b> — everything else in the deployment, including
 *       the databases and the broker, and the metadata service.
 *   <li><b>Multicast, broadcast and the wildcard address</b>, which are not places a merchant
 *       runs a web server and are places odd things happen.
 * </ul>
 *
 * <p>The check is on the <em>resolved addresses</em>, not on the hostname, because a hostname
 * is not an address until DNS says so and DNS answers to whoever controls it. A name that
 * resolves to 127.0.0.1 is exactly the attack this exists to stop, and it looks like an
 * ordinary hostname.
 *
 * <p>It also has to happen again at delivery time. DNS can change its mind between a merchant
 * registering an endpoint and this platform calling it, and a check that only ran at
 * registration is a check an attacker waits out.
 */
public final class SafeDestination {

    /** Why a destination was refused, in words a merchant can act on. */
    public record Refusal(String because) {
    }

    private SafeDestination() {
    }

    /**
     * @return empty if this platform is willing to call it, or why not
     */
    public static java.util.Optional<Refusal> check(String url) {
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException notAUrl) {
            return refuse("that is not a URL this platform can parse.");
        }

        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return refuse(
                    "a webhook endpoint has to be https. A delivery carries what you were paid "
                            + "and is signed with a shared secret, and clear text gives away "
                            + "both.");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            return refuse("that URL has no host.");
        }
        if (uri.getUserInfo() != null) {
            // https://evil.example@internal.host/ reads as evil.example to a human skimming it
            // and resolves to internal.host. Nothing legitimate needs it.
            return refuse("a webhook endpoint may not carry credentials in its URL.");
        }

        List<InetAddress> addresses;
        try {
            addresses = List.of(InetAddress.getAllByName(uri.getHost()));
        } catch (UnknownHostException unknown) {
            return refuse(
                    "no address could be found for " + uri.getHost() + ".");
        }

        for (InetAddress address : addresses) {
            // Every address it resolves to, not just the first. A host that answers with one
            // public address and one loopback address is not a host this platform will call.
            if (isNotOnTheInternet(address)) {
                return refuse(
                        uri.getHost()
                                + " resolves to "
                                + address.getHostAddress()
                                + ", which is not an address on the public internet. A webhook "
                                + "endpoint has to be somewhere this platform can reach from "
                                + "outside its own network.");
            }
        }

        return java.util.Optional.empty();
    }

    /** True for anything that is not a place a merchant runs a public web server. */
    private static boolean isNotOnTheInternet(InetAddress address) {
        return address.isLoopbackAddress()
                || address.isAnyLocalAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()
                || isCarrierGradeNatOrUniqueLocal(address);
    }

    /**
     * The ranges the JDK does not have a predicate for.
     *
     * <p>{@code isSiteLocalAddress} covers 10/8, 172.16/12 and 192.168/16 and the IPv6
     * equivalents it knew about, and misses carrier-grade NAT at 100.64/10 and IPv6 unique
     * local addresses at fc00::/7 — both of which are private networks by any useful
     * definition.
     */
    private static boolean isCarrierGradeNatOrUniqueLocal(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            return first == 100 && second >= 64 && second <= 127;
        }
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }

    private static java.util.Optional<Refusal> refuse(String because) {
        return java.util.Optional.of(new Refusal(because));
    }
}
