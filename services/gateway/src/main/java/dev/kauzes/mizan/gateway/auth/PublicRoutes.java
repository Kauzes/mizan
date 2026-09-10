package dev.kauzes.mizan.gateway.auth;

import java.util.List;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * The routes that do not need a token, written out one by one.
 *
 * <p>An allow list rather than a deny list, and a list rather than a pattern, because both of
 * the other shapes fail open: a new endpoint under a pattern somebody thought was public
 * becomes public without anyone deciding it should be. Anything not named here needs a token,
 * which means the failure mode of forgetting to update this file is a 401, not an open door.
 */
@Component
public class PublicRoutes {

    private static final PathPatternParser PARSER = PathPatternParser.defaultInstance;

    private record Route(HttpMethod method, PathPattern path) {

        boolean matches(ServerHttpRequest request) {
            return method.equals(request.getMethod())
                    && path.matches(PathContainer.parsePath(request.getPath().value()));
        }
    }

    private final List<Route> routes = List.of(
            // Nobody can present a token before they have one.
            route(HttpMethod.POST, "/api/v1/tokens"),
            route(HttpMethod.POST, "/api/v1/tokens/refresh"),

            // Ending a session is the refresh token's business, not the access token's. A
            // caller whose access token has already expired still has to be able to sign out,
            // and that is exactly the moment they are most likely to want to.
            route(HttpMethod.POST, "/api/v1/tokens/sign-out"),

            // What a role may do is the platform's own access model, not anybody's data: the
            // same table the API documentation prints, identical for every merchant. A console
            // needs it before it can decide what to show, which is before it has a token.
            route(HttpMethod.GET, "/api/v1/roles"),

            // Registration opens the first account, so it cannot require an account. This is
            // the one genuinely open write on the platform; MIZ-13's rate limiting is what
            // keeps it from being abused.
            route(HttpMethod.POST, "/api/v1/merchants"),

            // Whether the platform is up is not a secret, and a probe holds no credentials.
            route(HttpMethod.GET, "/actuator/health"),
            route(HttpMethod.GET, "/actuator/health/**"),
            route(HttpMethod.GET, "/internal/*/actuator/health"),
            route(HttpMethod.GET, "/internal/*/actuator/health/**"),

            // A published API contract is documentation. The rest of /internal/** is not, and
            // is no longer reachable without a token.
            route(HttpMethod.GET, "/internal/*/v3/api-docs"),
            route(HttpMethod.GET, "/internal/*/v3/api-docs/**"),
            route(HttpMethod.GET, "/v3/api-docs"),
            route(HttpMethod.GET, "/v3/api-docs/**"),
            route(HttpMethod.GET, "/swagger-ui.html"),
            route(HttpMethod.GET, "/swagger-ui/**"));

    public boolean isPublic(ServerHttpRequest request) {
        return routes.stream().anyMatch(route -> route.matches(request));
    }

    /** What is open, in the order it is declared, for a test to read back. */
    public List<String> describe() {
        return routes.stream().map(route -> route.method() + " " + route.path()).toList();
    }

    private static Route route(HttpMethod method, String pattern) {
        return new Route(method, PARSER.parse(pattern));
    }
}
