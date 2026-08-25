package dev.kauzes.mizan.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.kauzes.mizan.common.correlation.CorrelationContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class CorrelationIdFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void generatesAnIdWhenTheCallerSendsNone() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/correlation"))
                .andExpect(status().isOk())
                .andReturn();

        String seenByTheController = result.getResponse().getContentAsString();
        String echoed = result.getResponse().getHeader(CorrelationContext.HEADER);

        assertThat(seenByTheController).isNotBlank();
        assertThat(echoed).isEqualTo(seenByTheController);
    }

    @Test
    void keepsTheIdTheCallerSent() throws Exception {
        MvcResult result = mockMvc.perform(
                        get("/test/correlation").header(CorrelationContext.HEADER, "upstream-77"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).isEqualTo("upstream-77");
        assertThat(result.getResponse().getHeader(CorrelationContext.HEADER))
                .isEqualTo("upstream-77");
    }

    @Test
    void refusesAnIdThatCouldForgeALogLine() throws Exception {
        MvcResult result = mockMvc.perform(
                        get("/test/correlation").header(CorrelationContext.HEADER, "evil id;drop"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .isNotEqualTo("evil id;drop")
                .matches("[0-9a-f-]{36}");
    }

    @Test
    void theIdReachesTheErrorResponseToo() throws Exception {
        MvcResult result = mockMvc.perform(
                        get("/test/not-found").header(CorrelationContext.HEADER, "trace-abc"))
                .andExpect(status().isNotFound())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("\"correlationId\":\"trace-abc\"");
    }

    @Test
    void doesNotLeakTheIdOntoTheNextRequestOnTheSameThread() throws Exception {
        mockMvc.perform(get("/test/correlation").header(CorrelationContext.HEADER, "first-req"))
                .andExpect(status().isOk());

        assertThat(CorrelationContext.current()).isEmpty();
    }
}
