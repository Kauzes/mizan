package dev.kauzes.mizan.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import dev.kauzes.mizan.common.identity.Role;
import dev.kauzes.mizan.test.Callers;
import dev.kauzes.mizan.test.Idempotently;
import dev.kauzes.mizan.test.MizanIntegrationTest;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.json.JsonMapper;

/**
 * Make the platform handle a card, then read everything it wrote.
 *
 * <p>This is the check that catches the mistake nobody makes on purpose. A card number reaches
 * a log through a debug line added during an incident, a {@code toString()} on a request
 * object, an exception message that quotes what it was given, or a framework that logs a body
 * it was never asked to. None of those is caught by reading the code, because none of them
 * looks wrong where it is written — and a log store keeps what it is given for months.
 *
 * <p>So rather than trusting anybody's care, the real thing is driven — approved, declined,
 * a void, a request that fails validation — and every line produced in the meantime is read
 * back and searched. Not for the specific card: for <em>anything card shaped</em>, which is
 * what catches the one nobody thought of.
 *
 * <p>The platform already refuses to store a PAN. This asserts it does not write one either,
 * which is a different promise.
 */
@SpringBootTest(properties = {
    "mizan.acquirer.timeout=2s",
    // Everything this service and its libraries have to say. A check that only reads INFO is
    // a check that misses the debug line somebody left behind.
    "logging.level.dev.kauzes.mizan=TRACE",
    "logging.level.org.springframework.web=DEBUG"
})
class NothingSecretReachesTheLogTest extends MizanIntegrationTest {

    private static final String CARD = "4000000000000000";
    private static final String NO_FUNDS = "4000000000000002";

    /**
     * What a card number looks like, rather than what a long number looks like.
     *
     * <p>Thirteen to nineteen digits however somebody spaced them, beginning with a digit an
     * issuer actually uses, and passing the Luhn check. All three matter: "any run of digits"
     * flags every epoch timestamp and every Kafka offset, and a check that cries wolf is a
     * check somebody deletes. This is the same shape a card scanner uses.
     */
    private static final Pattern CARD_SHAPED =
            Pattern.compile("(?<![0-9A-Za-z-])([3-6](?:[ -]?[0-9]){12,18})(?![0-9A-Za-z])");

    /** Names that mean the value beside them was never meant to be read. */
    private static final List<String> NEVER_IN_A_LINE = List.of(
            "a-shared-secret-for-local-runs",
            "\"card\":\"",
            "card=4",
            "cardNumber",
            "pan=");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static ConfigurableApplicationContext acquirer;

    @BeforeAll
    static void startTheAcquirer() {
        acquirer = new SpringApplicationBuilder(
                        dev.kauzes.mizan.banksim.BankSimulatorApplication.class)
                .run("--spring.config.name=acquirer-test");
    }

    @AfterAll
    static void stopTheAcquirer() {
        if (acquirer != null) {
            acquirer.close();
        }
    }

    @DynamicPropertySource
    static void pointAtTheAcquirer(DynamicPropertyRegistry registry) {
        registry.add(
                "mizan.acquirer.base-url",
                () -> "http://localhost:"
                        + acquirer.getEnvironment().getProperty("local.server.port"));
    }

    @Autowired
    private MockMvc mockMvc;

    /**
     * Held in a list that tolerates being written to while it is read.
     *
     * <p>The platform's own background work — the outbox relay, a Kafka producer, a metrics
     * count — goes on logging while these assertions run, and the obvious appender keeps its
     * events in a plain list. Reading that while a scheduler appends to it fails the test for
     * a reason that has nothing to do with cards.
     */
    private final java.util.List<ILoggingEvent> everythingWritten =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    private ch.qos.logback.core.Appender<ILoggingEvent> listener;
    private ch.qos.logback.classic.Logger root;

    /**
     * The test harness talks too, and what it says is not what the platform wrote.
     *
     * <p>Spring's test context logs the configuration of the context it is building, and that
     * configuration includes this class's own fields. Reading that back and calling it a leak
     * would make the check fail on its own evidence.
     */
    private static final List<String> NOT_THE_PLATFORM = List.of(
            "org.springframework.test",
            "org.junit",
            "org.testcontainers",
            "com.github.dockerjava");

    @BeforeEach
    void listen() {
        root = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        root.setLevel(Level.DEBUG);

        everythingWritten.clear();
        listener = new ch.qos.logback.core.AppenderBase<>() {

            @Override
            protected void append(ILoggingEvent event) {
                everythingWritten.add(event);
            }
        };
        listener.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        listener.start();
        root.addAppender(listener);
    }

    @AfterEach
    void stopListening() {
        root.detachAppender(listener);
        listener.stop();
    }

    @Test
    void handlingRealCardsWritesNoCardToTheLog() throws Exception {
        Merchant merchant = merchant();

        // Everything a card touches on this service, in one go: an approval, a refusal, a
        // reservation released, and a request whose body is wrong.
        UUID approved = create(merchant);
        authorize(merchant, approved, CARD).andExpect(status().isOk());

        UUID refused = create(merchant);
        authorize(merchant, refused, NO_FUNDS).andExpect(status().isOk());

        UUID released = create(merchant);
        authorize(merchant, released, CARD).andExpect(status().isOk());
        mockMvc.perform(post(payments(merchant) + "/" + released + "/void")
                        .with(merchant.writer())
                        .with(Idempotently.freshKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"the customer cancelled\"}"))
                .andExpect(status().isOk());

        // The one most likely to quote what it was given back at the caller.
        mockMvc.perform(post(payments(merchant) + "/" + create(merchant) + "/authorize")
                .with(merchant.writer())
                .with(Idempotently.freshKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"card\":\"" + CARD + "x\"}"));

        String everything = whatWasWritten();

        // Reported as the offending lines rather than as "somewhere in forty kilobytes of
        // log", because the whole value of this check is being told where to look.
        assertThat(linesContaining(everything, CARD))
                .as("the approved card this run used reached the log")
                .isEmpty();
        assertThat(linesContaining(everything, NO_FUNDS))
                .as("the refused card this run used reached the log")
                .isEmpty();

        NEVER_IN_A_LINE.forEach(forbidden -> assertThat(linesContaining(everything, forbidden))
                .as("a line names %s, which means the value beside it is there too", forbidden)
                .isEmpty());

        // The general case, which is the one that catches the card nobody thought of.
        Matcher cardShaped = CARD_SHAPED.matcher(everything);
        while (cardShaped.find()) {
            String candidate = cardShaped.group().replaceAll("[ -]", "");
            if (!passesLuhn(candidate)) {
                continue;
            }
            assertThat(lineAround(everything, cardShaped.start()))
                    .as("something that passes for a card number was logged: %s", candidate)
                    .isEmpty();
        }
    }

    @Test
    void butTheLastFourAreFineBecauseTheyAreWhatAMerchantIsShown() throws Exception {
        Merchant merchant = merchant();
        UUID payment = create(merchant);
        authorize(merchant, payment, CARD).andExpect(status().isOk());

        // The rule is precise rather than blanket. Four digits are what this platform keeps,
        // returns and prints on a receipt; a rule that forbade them would be a rule people
        // work around rather than one they keep.
        assertThat(whatWasWritten()).doesNotContain(CARD);
        assertThat(CARD_SHAPED.matcher("0000").find())
                .as("four digits are not card shaped")
                .isFalse();
    }

    /** Every message and every stack trace the platform wrote, as one text to search. */
    private String whatWasWritten() {
        StringBuilder all = new StringBuilder();
        List<ILoggingEvent> snapshot;
        synchronized (everythingWritten) {
            snapshot = List.copyOf(everythingWritten);
        }
        snapshot.stream()
                .filter(event -> NOT_THE_PLATFORM.stream()
                        .noneMatch(harness -> event.getLoggerName().startsWith(harness)))
                .forEach(event -> {
                    all.append(event.getLoggerName())
                            .append(" | ")
                            .append(event.getFormattedMessage())
                            .append('\n');
                    if (event.getThrowableProxy()
                            instanceof ch.qos.logback.classic.spi.ThrowableProxy proxy) {
                        StringWriter trace = new StringWriter();
                        proxy.getThrowable().printStackTrace(new PrintWriter(trace));
                        all.append(trace).append('\n');
                    }
                });
        return all.toString();
    }

    /**
     * The check every card number satisfies and most long numbers do not.
     *
     * <p>Not security: it is what tells a card apart from an offset, an epoch or an account
     * number, so that this test fails only when it has found something.
     */
    private static boolean passesLuhn(String digits) {
        int sum = 0;
        boolean doubling = false;
        for (int at = digits.length() - 1; at >= 0; at--) {
            int digit = digits.charAt(at) - '0';
            if (doubling) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubling = !doubling;
        }
        return sum % 10 == 0;
    }

    /** The lines that hold something they should not, named so somebody can go and look. */
    private static List<String> linesContaining(String everything, String forbidden) {
        return everything.lines().filter(line -> line.contains(forbidden)).toList();
    }

    private static String lineAround(String text, int position) {
        int from = text.lastIndexOf('\n', position) + 1;
        int to = text.indexOf('\n', position);
        return text.substring(from, to < 0 ? text.length() : to);
    }

    private org.springframework.test.web.servlet.ResultActions authorize(
            Merchant merchant, UUID payment, String card) throws Exception {

        return mockMvc.perform(post(payments(merchant) + "/" + payment + "/authorize")
                .with(merchant.writer())
                .with(Idempotently.freshKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"card\":\"" + card + "\"}"));
    }

    private UUID create(Merchant merchant) throws Exception {
        String body = mockMvc.perform(post(payments(merchant))
                        .with(merchant.writer())
                        .with(Idempotently.freshKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":125000,"currency":"TRY","reference":"order-%s"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        return UUID.fromString(JSON.readTree(body).path("id").asString());
    }

    private static String payments(Merchant merchant) {
        return "/api/v1/merchants/" + merchant.id() + "/payments";
    }

    private record Merchant(UUID id, UUID userId) {

        RequestPostProcessor writer() {
            return Callers.as(userId, id, Role.ADMIN);
        }
    }

    private static Merchant merchant() {
        return new Merchant(UUID.randomUUID(), UUID.randomUUID());
    }
}
