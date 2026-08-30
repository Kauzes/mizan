package dev.kauzes.mizan.common.web;

import dev.kauzes.mizan.common.identity.Permission;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * What a caller must be allowed to do before this endpoint runs.
 *
 * <p>Declared rather than checked in the method body, so that what an endpoint requires can
 * be read from its signature and so that a missing declaration is something the platform can
 * notice. An endpoint under {@code /api/} carrying neither this nor {@link PublicEndpoint}
 * stops the service from starting.
 *
 * <p>Where the path names a merchant, the caller must also be acting for that merchant. That
 * check is not optional and is not declared here, because an endpoint that wanted to opt out
 * of it would be an endpoint that reads across the tenant boundary.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequiresPermission {

    Permission value();
}
