package dev.kauzes.mizan.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import dev.kauzes.mizan.common.correlation.CorrelationContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * That the correlation id actually leaves this service on an outbound call.
 *
 * <p>{@link CorrelationPropagationInterceptorTest} proves the interceptor does its job when
 * something calls it, which was never the question. From MIZ-22 until tracing made it visible,
 * nothing did: the interceptor was a bean, was correct, and was attached to no client at all,
 * so every service-to-service call arrived with no header and the receiving service generated
 * an id of its own. One payment therefore had three correlation ids, and the id a merchant
 * read out matched only the first hop.
 *
 * <p>Nothing caught it. The wiring test asserted the bean existed. The unit test called the
 * interceptor directly. Both passed for months.
 *
 * <p>So this one builds a client the way a service builds one — from the injected builder,
 * with whatever the platform has customised onto it — and asserts about the request that comes
 * out the other end. It is the same lesson as MIZ-41, MIZ-47 and MIZ-55: a component that is
 * correct and unreachable looks exactly like a component that works.
 */
@SpringBootTest(classes = TestApplication.class)
class CorrelationReallyPropagatesTest {

    @Autowired
    private RestClient.Builder builder;

    @AfterEach
    void clear() {
        CorrelationContext.clear();
    }

    @Test
    void aClientBuiltTheWayAServiceBuildsOneCarriesTheId() {
        CorrelationContext.set("inbound-77");

        // Bound before the client is built: binding swaps the builder's request factory, and
        // a client built beforehand keeps the real one and goes looking for a host.
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://ledger.invalid/entries"))
                .andExpect(header(CorrelationContext.HEADER, "inbound-77"))
                .andRespond(withSuccess());

        RestClient client = builder.build();
        String answer =
                client.get().uri("http://ledger.invalid/entries").retrieve().body(String.class);

        assertThat(answer).isNull();
        server.verify();
    }

    @Test
    void andSendsNothingWhenThereIsNoIdToSend() {
        // Work on a scheduler rather than in a request. An empty header would be worse than
        // none: the receiving service would take it as an id and log an empty one.
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://ledger.invalid/entries"))
                .andExpect(request -> assertThat(
                                request.getHeaders().get(CorrelationContext.HEADER))
                        .as("no id here, so no header")
                        .isNull())
                .andRespond(withSuccess());

        builder.build().get().uri("http://ledger.invalid/entries").retrieve().body(String.class);

        server.verify();
    }
}
