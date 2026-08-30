package dev.kauzes.mizan.common.web;

import dev.kauzes.mizan.common.error.ForbiddenException;
import dev.kauzes.mizan.common.identity.Caller;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Decides whether the caller the gateway established may do what they are asking to do.
 *
 * <p>Two checks, in this order. The caller must be acting for the merchant named in the path,
 * and must hold a role carrying the permission the endpoint declares. Both are refused the
 * same way, before anything is looked up, so a refusal says nothing about whether the thing
 * refused exists: asking about another merchant's payment and asking about a payment that
 * was never created are answered identically.
 *
 * <p>An endpoint that declares neither a permission nor {@link PublicEndpoint} is refused.
 * The service will not have started in that state — {@link AuthorizationDeclarations} sees to
 * that — so this is the second line rather than the first.
 */
public class AuthorizationInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationInterceptor.class);

    /** The path variable naming a tenant. An endpoint that has it is scoped to it. */
    static final String MERCHANT_PATH_VARIABLE = "merchantId";

    private static final String REFUSED = "You may not do that.";

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {

        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }

        if (method.hasMethodAnnotation(PublicEndpoint.class)) {
            return true;
        }

        RequiresPermission required = method.getMethodAnnotation(RequiresPermission.class);
        if (required == null) {
            log.error(
                    "{} declares no permission, so the request was refused",
                    method.getShortLogMessage());
            throw new ForbiddenException(REFUSED);
        }

        Caller caller = CallerHeaders.of(request);

        UUID merchant = merchantIn(request);
        if (merchant != null && !caller.actsFor(merchant)) {
            log.warn(
                    "user {} of merchant {} asked about merchant {}",
                    caller.userId(),
                    caller.merchantId(),
                    merchant);
            throw new ForbiddenException(REFUSED);
        }

        if (!caller.can(required.value())) {
            log.warn(
                    "user {} holding {} lacks {}",
                    caller.userId(),
                    caller.roles(),
                    required.value());
            throw new ForbiddenException(REFUSED);
        }

        return true;
    }

    private static UUID merchantIn(HttpServletRequest request) {
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(variables instanceof Map<?, ?> byName)) {
            return null;
        }

        Object value = byName.get(MERCHANT_PATH_VARIABLE);
        if (value == null) {
            return null;
        }

        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException notAnId) {
            // Not a merchant id, so it is nobody's merchant. Refused rather than allowed
            // through to a handler that would have to decide what to do with it.
            throw new ForbiddenException(REFUSED);
        }
    }
}
