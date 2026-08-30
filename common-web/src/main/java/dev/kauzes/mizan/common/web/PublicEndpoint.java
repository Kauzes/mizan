package dev.kauzes.mizan.common.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an endpoint that deliberately needs no caller: registering, signing in, refreshing.
 *
 * <p>The gateway keeps the list that actually opens these to the internet. This says the same
 * thing at the other end, so that an endpoint is never open merely because nobody wrote down
 * what it required.
 *
 * @see RequiresPermission
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface PublicEndpoint {

    /** Why this one needs nobody, in a few words. Read by people, not by code. */
    String because();
}
