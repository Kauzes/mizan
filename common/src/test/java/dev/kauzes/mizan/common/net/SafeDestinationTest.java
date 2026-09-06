package dev.kauzes.mizan.common.net;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What this platform will and will not be talked into calling.
 *
 * <p>These are the cases that turn a webhook feature into a way to make requests inside
 * somebody else's network. Worth being explicit about each one, because the failure is silent
 * and useful to an attacker rather than loud and useful to nobody.
 */
class SafeDestinationTest {

    @Test
    void willCallAnOrdinaryHttpsEndpoint() {
        assertThat(SafeDestination.check("https://example.com/webhooks/mizan")).isEmpty();
    }

    @Test
    void willNotCallOverHttp() {
        assertThat(SafeDestination.check("http://example.com/webhooks"))
                .get()
                .extracting(SafeDestination.Refusal::because)
                .asString()
                .contains("has to be https");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://127.0.0.1/hook",
        "https://localhost/hook",
        "https://[::1]/hook",
        "https://0.0.0.0/hook",
        "https://10.0.0.5/hook",
        "https://192.168.1.1/hook",
        "https://172.16.4.4/hook",
        // The one that hands out cloud credentials to whoever asks.
        "https://169.254.169.254/latest/meta-data/",
        // Carrier-grade NAT, which the JDK does not consider site-local.
        "https://100.64.1.1/hook",
        // An IPv6 unique local address, likewise.
        "https://[fd00::1]/hook"
    })
    void willNotCallAnythingThatIsNotOnTheInternet(String url) {
        assertThat(SafeDestination.check(url))
                .as("%s should be refused", url)
                .isNotEmpty();
    }

    @Test
    void willNotCallAUrlThatCarriesCredentials() {
        // Reads as example.com to anybody skimming it, and resolves to the other one.
        assertThat(SafeDestination.check("https://example.com@127.0.0.1/hook"))
                .get()
                .extracting(SafeDestination.Refusal::because)
                .asString()
                .contains("credentials in its URL");
    }

    @Test
    void refusesThingsThatAreNotUrlsAtAll() {
        assertThat(SafeDestination.check("not a url")).isNotEmpty();
        assertThat(SafeDestination.check("https:///nohost")).isNotEmpty();
    }

    @Test
    void saysWhichAddressItObjectedTo() {
        // A merchant who mistypes an internal address should be told what happened rather
        // than left with "invalid".
        assertThat(SafeDestination.check("https://10.1.2.3/hook"))
                .get()
                .extracting(SafeDestination.Refusal::because)
                .asString()
                .contains("10.1.2.3")
                .contains("not an address on the public internet");
    }
}
