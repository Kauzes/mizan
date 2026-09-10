package dev.kauzes.mizan.identity.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kauzes.mizan.test.MizanIntegrationTest;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The half of signing in that exists for browsers.
 *
 * <p>A refresh token is a credential for days, and a console is the one client on this
 * platform that cannot be trusted to store one: local storage is readable by any script that
 * reaches the page. So the same token also goes out as a cookie the page cannot read, and
 * every assertion here is about that cookie being the one a browser will actually keep, send
 * only where it should, and let go of on the way out.
 *
 * <p>{@code secure} is turned on for this test rather than left at whatever the local stack
 * uses, because a cookie carrying a session over plain HTTP anywhere real is the failure this
 * flag exists to prevent, and a default is a thing that changes quietly.
 */
@SpringBootTest(properties = "mizan.security.session-cookie.secure=true")
class SessionCookieTest extends MizanIntegrationTest {

    private static final String PASSWORD = "a-long-enough-password";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void signingInSetsACookieThePageCannotRead() throws Exception {
        MvcResult signedIn = signIn(registerAMerchant()).andExpect(status().isOk()).andReturn();

        String header = signedIn.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(header)
                .as("a script on the page can read a cookie without this, and a stolen refresh "
                        + "token is a session somebody keeps rather than merely uses")
                .contains("HttpOnly")
                .contains("Secure")
                .contains("SameSite=Strict")
                // Every other request carries an access token in a header, so there is no
                // reason for this to travel with them: a credential sent on every request is
                // a credential logged by every proxy.
                .contains("Path=/api/v1/tokens");

        Cookie cookie = signedIn.getResponse().getCookie("mizan_refresh");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue())
                .as("and it carries the same token the body did, so nothing else changed")
                .isEqualTo(bodyOf(signedIn).path("refreshToken").asText());
        assertThat(cookie.getMaxAge())
                .as("it has to outlive the tab, or a reload is a sign in")
                .isEqualTo(2592000);
    }

    @Test
    void aBrowserRefreshesWithNoBodyAtAll() throws Exception {
        Cookie held = cookieFrom(signIn(registerAMerchant()));

        MvcResult refreshed = mockMvc.perform(post("/api/v1/tokens/refresh").cookie(held))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn();

        assertThat(refreshed.getResponse().getCookie("mizan_refresh"))
                .as("the refresh token rotates, so the cookie has to rotate with it")
                .isNotNull()
                .extracting(Cookie::getValue)
                .isNotEqualTo(held.getValue());
    }

    @Test
    void aNamedTokenBeatsWhateverTheBrowserIsCarrying() throws Exception {
        Cookie somebodyElses = cookieFrom(signIn(registerAMerchant()));
        String mine = bodyOf(signIn(registerAMerchant()).andReturn())
                .path("refreshToken")
                .asText();

        // A client that named a token meant that one. Spending a different one because a
        // cookie happened to be attached ends with somebody's session revoked for a replay
        // they did not commit.
        MvcResult refreshed = mockMvc.perform(post("/api/v1/tokens/refresh")
                        .cookie(somebodyElses)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + mine + "\"}"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(refreshed.getResponse().getCookie("mizan_refresh")).isNotNull();

        // The cookie's own token was never spent, and still works.
        mockMvc.perform(post("/api/v1/tokens/refresh").cookie(somebodyElses))
                .andExpect(status().isOk());
    }

    @Test
    void presentingNothingAtAllIsRefusedLikeAWrongToken() throws Exception {
        mockMvc.perform(post("/api/v1/tokens/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void signingOutEndsTheSessionAndTakesTheCookieWithIt() throws Exception {
        Cookie held = cookieFrom(signIn(registerAMerchant()));

        MvcResult signedOut = mockMvc.perform(post("/api/v1/tokens/sign-out").cookie(held))
                .andExpect(status().isNoContent())
                .andReturn();

        Cookie cleared = signedOut.getResponse().getCookie("mizan_refresh");
        assertThat(cleared).isNotNull();
        assertThat(cleared.getValue()).isEmpty();
        assertThat(cleared.getMaxAge())
                .as("a browser matches on name and path, so a deletion that differs in either "
                        + "leaves the original in place")
                .isZero();
        assertThat(cleared.getPath()).isEqualTo("/api/v1/tokens");

        // And the token itself is dead, not merely forgotten by one browser.
        mockMvc.perform(post("/api/v1/tokens/refresh").cookie(held))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void signingOutTwiceIsNotAnErrorAndTellsNobodyAnything() throws Exception {
        Cookie held = cookieFrom(signIn(registerAMerchant()));

        mockMvc.perform(post("/api/v1/tokens/sign-out").cookie(held))
                .andExpect(status().isNoContent());

        // The same answer for a spent token, and for one this service never issued. Anything
        // else would be a way to ask whether a stolen token still works.
        mockMvc.perform(post("/api/v1/tokens/sign-out").cookie(held))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/tokens/sign-out")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"never-issued\"}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/tokens/sign-out")).andExpect(status().isNoContent());
    }

    private Cookie cookieFrom(ResultActions signedIn) throws Exception {
        Cookie cookie = signedIn.andExpect(status().isOk()).andReturn().getResponse()
                .getCookie("mizan_refresh");
        assertThat(cookie).isNotNull();
        return cookie;
    }

    private String registerAMerchant() throws Exception {
        String email = "owner-" + UUID.randomUUID() + "@kauzes.dev";
        mockMvc.perform(post("/api/v1/merchants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"merchantName":"Kauzes Coffee","email":"%s",\
                                "password":"%s","fullName":"Sam Kauzes"}
                                """.formatted(email, PASSWORD)))
                .andExpect(status().isCreated());
        return email;
    }

    private ResultActions signIn(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/tokens")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s"}
                        """.formatted(email, PASSWORD)));
    }

    private static JsonNode bodyOf(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
