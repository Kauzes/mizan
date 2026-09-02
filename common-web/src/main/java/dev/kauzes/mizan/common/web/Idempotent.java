package dev.kauzes.mizan.common.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * This endpoint does something it would be harmful to do twice, so it requires an
 * {@code Idempotency-Key} and answers a repeat with what the first call produced.
 *
 * <p>Declared rather than implemented per endpoint, so that what a caller has to send can be
 * read from the signature, and so a missing declaration is something the platform can notice.
 * A write under {@code /api/} carrying neither this nor {@link NotIdempotent} stops the
 * service from starting, for the same reason {@link RequiresPermission} does.
 *
 * <p>The key is scoped to the merchant and to the endpoint. Two merchants, or one merchant on
 * two operations, cannot collide, and a caller can use the same key for the whole of one
 * business action without its two calls being confused for each other.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Idempotent {
}
