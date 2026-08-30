package dev.kauzes.mizan.common.web;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Refuses to let a service start with an endpoint nobody decided the rules for.
 *
 * <p>Forgetting to say what an endpoint requires is the ordinary way an API grows a hole, and
 * it is invisible: the endpoint works, which is exactly what it looks like when it is
 * correct. Checking at startup turns that into a failure on the first run rather than a
 * finding much later.
 *
 * <p>Only endpoints under {@code /api/} are checked. Actuator and the generated documentation
 * are the gateway's business, and are not this platform's handlers to annotate.
 */
public class AuthorizationDeclarations {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationDeclarations.class);

    private static final String GUARDED_PREFIX = "/api/";

    private final ObjectProvider<RequestMappingHandlerMapping> mappings;

    public AuthorizationDeclarations(ObjectProvider<RequestMappingHandlerMapping> mappings) {
        this.mappings = mappings;
    }

    @EventListener(ContextRefreshedEvent.class)
    public void check() {
        // Actuator contributes a mapping of its own, so this looks at every one of them
        // rather than expecting a single bean. Nothing but this platform's controllers is
        // mapped under /api/, so the extra mappings contribute nothing and cost nothing.
        List<String> undeclared = mappings.orderedStream()
                .flatMap(mapping -> mapping.getHandlerMethods().entrySet().stream())
                .filter(AuthorizationDeclarations::isGuarded)
                .filter(entry -> !declares(entry.getValue()))
                .map(entry -> entry.getValue().getShortLogMessage())
                .sorted()
                .toList();

        if (!undeclared.isEmpty()) {
            throw new IllegalStateException(
                    "these endpoints say nothing about who may call them. Add "
                            + "@RequiresPermission, or @PublicEndpoint if that is really the "
                            + "intent: " + String.join(", ", undeclared));
        }

        log.debug("every endpoint under {} declares what it requires", GUARDED_PREFIX);
    }

    private static boolean isGuarded(Map.Entry<RequestMappingInfo, HandlerMethod> entry) {
        return entry.getKey().getPathPatternsCondition() != null
                && entry.getKey().getPathPatternsCondition().getPatterns().stream()
                        .anyMatch(pattern ->
                                pattern.getPatternString().startsWith(GUARDED_PREFIX));
    }

    private static boolean declares(HandlerMethod method) {
        return method.hasMethodAnnotation(RequiresPermission.class)
                || method.hasMethodAnnotation(PublicEndpoint.class);
    }
}
