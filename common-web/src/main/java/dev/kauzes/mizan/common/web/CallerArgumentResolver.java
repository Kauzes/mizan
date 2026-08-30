package dev.kauzes.mizan.common.web;

import dev.kauzes.mizan.common.identity.Caller;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Lets a controller ask for the {@link Caller} instead of reading headers. Anything a
 * handler does on behalf of somebody starts from this, which is easier to notice missing
 * than a merchant id that was never consulted.
 */
public class CallerArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return Caller.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer container,
            NativeWebRequest request,
            WebDataBinderFactory binderFactory) {

        return CallerHeaders.of(request.getNativeRequest(HttpServletRequest.class));
    }
}
