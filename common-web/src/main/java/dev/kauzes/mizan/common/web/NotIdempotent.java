package dev.kauzes.mizan.common.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * This endpoint needs no key, and here is why.
 *
 * <p>There are three honest reasons. It is already safe to repeat, as a delete or a replace
 * is. It carries its own idempotency, as posting to the journal does with its external
 * reference. Or it has no merchant to scope a key to, as registering and signing in do.
 *
 * <p>Written out rather than left as an absence, because an endpoint that needs no key and an
 * endpoint nobody thought about look identical from the outside, and only one of them is
 * fine.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface NotIdempotent {

    /** Why this one is safe to repeat, or handles repetition itself. Read by people. */
    String because();
}
