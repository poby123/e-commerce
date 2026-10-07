package com.ecommerce.common.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Restricts a controller (or one handler) to callers whose {@link AuthHeaders#USER_ROLES} contain the role.
 * The gateway authenticates; each service decides what a role may do with its own resources.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresRole {

    String value();
}
