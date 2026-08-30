package dev.kauzes.mizan.identity.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * A record prints every field it holds, which is exactly how a password reaches a log line
 * that only meant to say what came in. This one does not.
 */
class RegisterMerchantRequestTest {

    @Test
    void redactsThePasswordWhenPrinted() {
        RegisterMerchantRequest request =
                new RegisterMerchantRequest(
                        "Kauzes Coffee", "owner@kauzes.dev", "a-long-enough-password", "Sam Kauzes");

        assertThat(request.toString())
                .doesNotContain("a-long-enough-password")
                .contains("password=***")
                .as("the rest of the request is still worth printing")
                .contains("Kauzes Coffee", "owner@kauzes.dev");
    }
}
