package dev.kauzes.mizan.common.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CorrelationContextTest {

    @AfterEach
    void clear() {
        CorrelationContext.clear();
    }

    @Test
    void holdsAndClearsTheCurrentId() {
        assertThat(CorrelationContext.current()).isEmpty();
        assertThat(CorrelationContext.currentOrEmpty()).isEmpty();

        CorrelationContext.set("abc123");
        assertThat(CorrelationContext.current()).contains("abc123");

        CorrelationContext.clear();
        assertThat(CorrelationContext.current()).isEmpty();
    }

    @Test
    void keepsAnIdThatCameFromUpstream() {
        assertThat(CorrelationContext.sanitiseOrGenerate("9f8c-42_AB")).isEqualTo("9f8c-42_AB");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "has spaces",
        "line\nbreak",
        "semi;colon",
        "quote\"mark",
        "slash/es",
        "unicode-ç",
        ""
    })
    void replacesAnythingThatCouldForgeALogLine(String hostile) {
        String result = CorrelationContext.sanitiseOrGenerate(hostile);

        assertThat(result).isNotEqualTo(hostile).matches("[0-9a-f-]{36}");
    }

    @Test
    void replacesAnIdThatIsTooLongToBeReal() {
        String tooLong = "a".repeat(65);

        assertThat(CorrelationContext.sanitiseOrGenerate(tooLong)).isNotEqualTo(tooLong);
        assertThat(CorrelationContext.sanitiseOrGenerate("a".repeat(64))).hasSize(64);
    }

    @Test
    void replacesAMissingId() {
        assertThat(CorrelationContext.sanitiseOrGenerate(null)).isNotBlank();
    }

    @Test
    void generatesADifferentIdEveryTime() {
        assertThat(CorrelationContext.generate()).isNotEqualTo(CorrelationContext.generate());
    }
}
