package dev.kauzes.mizan.identity.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.kauzes.mizan.common.identity.Role;
import dev.kauzes.mizan.identity.Callers;
import dev.kauzes.mizan.test.MizanIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Registration is the first thing the platform can be asked to do, and the first place it
 * handles something it must never give back.
 */
@SpringBootTest
class MerchantRegistrationTest extends MizanIntegrationTest {

    private static final String PASSWORD = "a-long-enough-password";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void registersAMerchantAndItsOwnerTogether() throws Exception {
        String email = freshEmail();

        register("Kauzes Coffee", email, PASSWORD)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.merchant.id").isNotEmpty())
                .andExpect(jsonPath("$.merchant.name").value("Kauzes Coffee"))
                .andExpect(jsonPath("$.owner.email").value(email))
                .andExpect(jsonPath("$.owner.roles[0]").value(Role.OWNER.name()));

        assertThat(count("select count(*) from app_user where email = ?", email))
                .as("the owner should exist")
                .isOne();
    }

    @Test
    void pointsAtTheMerchantItJustCreated() throws Exception {
        String email = freshEmail();
        ResultActions registration = register("Kauzes Books", email, PASSWORD);
        String location = registration.andReturn().getResponse().getHeader("Location");

        assertThat(location).as("a created resource says where it is").isNotNull();
        mockMvc.perform(get(location).with(ownerOf(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Kauzes Books"));
    }

    @Test
    void neverReturnsThePasswordOrItsHash() throws Exception {
        String email = freshEmail();
        String registration = bodyOf(register("Kauzes Tea", email, PASSWORD));

        assertThat(registration).doesNotContain(PASSWORD).doesNotContain("passwordHash");

        String users = bodyOf(
                mockMvc.perform(get(usersOfMerchantWith(email)).with(ownerOf(email))));
        assertThat(users).doesNotContain(PASSWORD).doesNotContain("passwordHash");
        assertThat(users).as("the owner should be listed").contains(email);
    }

    @Test
    void storesThePasswordAsASaltedHash() throws Exception {
        String first = freshEmail();
        String second = freshEmail();
        register("Kauzes One", first, PASSWORD).andExpect(status().isCreated());
        register("Kauzes Two", second, PASSWORD).andExpect(status().isCreated());

        String firstHash = hashOf(first);
        String secondHash = hashOf(second);

        assertThat(firstHash).as("a stored password is not a password").isNotEqualTo(PASSWORD);
        assertThat(firstHash)
                .as("bcrypt, which is salted and deliberately slow")
                .startsWith("$2");
        assertThat(passwordEncoder.matches(PASSWORD, firstHash))
                .as("the stored hash should verify the password it was made from")
                .isTrue();
        assertThat(firstHash)
                .as("the same password twice should not produce the same hash")
                .isNotEqualTo(secondHash);
    }

    @Test
    void refusesASecondAccountForAnEmailAlreadyTaken() throws Exception {
        String email = freshEmail();
        register("Kauzes First", email, PASSWORD).andExpect(status().isCreated());

        register("Kauzes Second", email, PASSWORD)
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void treatsAnAddressAsTakenWhateverCaseItIsTypedIn() throws Exception {
        String email = freshEmail();
        register("Kauzes Lower", email, PASSWORD).andExpect(status().isCreated());

        register("Kauzes Upper", email.toUpperCase(java.util.Locale.ROOT), PASSWORD)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void leavesNoMerchantBehindWhenTheEmailIsTaken() throws Exception {
        String email = freshEmail();
        register("Kauzes Original", email, PASSWORD).andExpect(status().isCreated());

        long before = count("select count(*) from merchant");
        register("Kauzes Doomed", email, PASSWORD).andExpect(status().isConflict());

        assertThat(count("select count(*) from merchant"))
                .as("a merchant nobody can sign in to should never be left behind")
                .isEqualTo(before);
        assertThat(count("select count(*) from merchant where name = ?", "Kauzes Doomed"))
                .isZero();
    }

    @Test
    void namesTheFieldsThatFailedValidation() throws Exception {
        String body =
                """
                {"merchantName":"","email":"not-an-address","password":"short","fullName":""}
                """;

        mockMvc.perform(
                        post("/api/v1/merchants")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field")
                        .value(org.hamcrest.Matchers.hasItems(
                                "merchantName", "email", "password", "fullName")));
    }

    @Test
    void neverLogsThePassword() throws Exception {
        ch.qos.logback.classic.Logger root =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        Level original = root.getLevel();
        root.setLevel(Level.DEBUG);
        root.addAppender(captured);

        try {
            String email = freshEmail();
            register("Kauzes Quiet", email, PASSWORD).andExpect(status().isCreated());
            register("Kauzes Quiet Again", email, PASSWORD).andExpect(status().isConflict());
        } finally {
            root.detachAppender(captured);
            root.setLevel(original);
            captured.stop();
        }

        List<String> lines = captured.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(lines)
                .as("a password should not survive a trip through the logs")
                .noneMatch(line -> line.contains(PASSWORD));
        assertThat(lines).as("something should have been logged, or this proves nothing").isNotEmpty();
    }

    @Test
    void refusesAMerchantThatIsNotTheCallersWithoutSayingWhetherItExists() throws Exception {
        String email = freshEmail();
        register("Kauzes Private", email, PASSWORD).andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/merchants/" + UUID.randomUUID()).with(ownerOf(email)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.detail").value("You may not do that."));
    }

    @Test
    void refusesARequestThatDidNotComeThroughTheGateway() throws Exception {
        String email = freshEmail();
        register("Kauzes Direct", email, PASSWORD).andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/merchants/" + merchantIdOf(email)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    private ResultActions register(String merchantName, String email, String password)
            throws Exception {

        String body =
                """
                {"merchantName":"%s","email":"%s","password":"%s","fullName":"Sam Kauzes"}
                """
                        .formatted(merchantName, email, password);

        return mockMvc.perform(
                post("/api/v1/merchants").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** Registrations commit, so every test brings its own address rather than sharing one. */
    private static String freshEmail() {
        return "owner-" + UUID.randomUUID() + "@kauzes.dev";
    }

    private String usersOfMerchantWith(String email) {
        return "/api/v1/merchants/" + merchantIdOf(email) + "/users";
    }

    private UUID merchantIdOf(String email) {
        return jdbc.queryForObject(
                "select merchant_id from app_user where email = ?", UUID.class, email);
    }

    /** The owner this registration created, as the gateway would have established them. */
    private org.springframework.test.web.servlet.request.RequestPostProcessor ownerOf(
            String email) {

        UUID userId = jdbc.queryForObject(
                "select id from app_user where email = ?", UUID.class, email);
        return Callers.owner(userId, merchantIdOf(email));
    }

    private String hashOf(String email) {
        return jdbc.queryForObject(
                "select password_hash from app_user where email = ?", String.class, email);
    }

    private long count(String sql, Object... arguments) {
        Long counted = jdbc.queryForObject(sql, Long.class, arguments);
        return counted == null ? 0 : counted;
    }

    private static String bodyOf(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
