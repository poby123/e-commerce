package com.ecommerce.common.web;

/**
 * Headers the gateway sets after it has verified the access token.
 * The gateway strips any client-supplied copies, so services behind it can trust them.
 */
public final class AuthHeaders {

    public static final String USER_ID = "X-User-Id";
    public static final String USER_ROLES = "X-User-Roles";

    private AuthHeaders() {
    }
}
