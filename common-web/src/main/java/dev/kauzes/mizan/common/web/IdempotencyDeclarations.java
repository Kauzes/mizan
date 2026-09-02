package dev.kauzes.mizan.common.web;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Refuses to let a service start with a write nobody decided the repeat behaviour of.
 *
 * <p>The same argument as {@link AuthorizationDeclarations}, applied to a different question.
 * An endpoint that quietly does its work twice looks exactly like one that correctly does it
 * once, right up until a customer is charged twice, and nothing about the code says which it
 * is. Making the decision compulsory is what turns that into a failure on the first run.
 */
public class IdempotencyDeclarations {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyDeclarations.class);

    private static final String GUARDED_PREFIX = "/api/";

    private static final Set<RequestMethod> WRITES = Set.of(
            RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE);

    private final ObjectProvider<RequestMappingHandlerMapping> mappings;

    public IdempotencyDeclarations(ObjectProvider<RequestMappingHandlerMapping> mappings) {
        this.mappings = mappings;
    }

    @EventListener(ContextRefreshedEvent.class)
    public void check() {
        List<String> undeclared = mappings.orderedStream()
                .flatMap(mapping -> mapping.getHandlerMethods().entrySet().stream())
                .filter(IdempotencyDeclarations::isAGuardedWrite)
                .filter(entry -> !declares(entry.getValue()))
                .map(entry -> entry.getValue().getShortLogMessage())
                .sorted()
                .toList();

        if (!undeclared.isEmpty()) {
            throw new IllegalStateException(
                    "these writes say nothing about what happens when they are repeated. Add "
                            + "@Idempotent, or @NotIdempotent if repeating them is genuinely "
                            + "harmless: " + String.join(", ", undeclared));
        }

        log.debug("every write under {} says what a repeat of it does", GUARDED_PREFIX);
    }

    private static boolean isAGuardedWrite(Map.Entry<RequestMappingInfo, HandlerMethod> entry) {
        boolean underOurApi = entry.getKey().getPathPatternsCondition() != null
                && entry.getKey().getPathPatternsCondition().getPatterns().stream()
                        .anyMatch(pattern -> pattern.getPatternString().startsWith(GUARDED_PREFIX));

        boolean writes = entry.getKey().getMethodsCondition().getMethods().stream()
                .anyMatch(WRITES::contains);

        return underOurApi && writes;
    }

    private static boolean declares(HandlerMethod method) {
        return method.hasMethodAnnotation(Idempotent.class)
                || method.hasMethodAnnotation(NotIdempotent.class);
    }
}
