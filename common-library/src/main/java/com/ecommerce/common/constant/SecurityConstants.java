package com.ecommerce.common.constant;

public final class SecurityConstants {

    private SecurityConstants() {}

    // HTTP Headers
    public static final String HEADER_CORRELATION_ID = "X-Correlation-Id";
    public static final String HEADER_GATEWAY_ASSERTION = "X-Gateway-Assertion";
    public static final String HEADER_CALLING_SERVICE = "X-Calling-Service";
    public static final String HEADER_INTERNAL_SERVICE_KEY = "X-Internal-Service-Key";

    // Standard User Roles
    public static final String ROLE_CUSTOMER = "ROLE_CUSTOMER";
    public static final String ROLE_MERCHANT = "ROLE_MERCHANT";
    public static final String ROLE_ADMINISTRATOR = "ROLE_ADMINISTRATOR";

    // Scoped Internal Service Roles
    public static final String ROLE_SERVICE_ORDER = "ROLE_SERVICE_ORDER";
    public static final String ROLE_SERVICE_PAYMENT = "ROLE_SERVICE_PAYMENT";
    public static final String ROLE_SERVICE_INVENTORY = "ROLE_SERVICE_INVENTORY";
    public static final String ROLE_SERVICE_NOTIFICATION = "ROLE_SERVICE_NOTIFICATION";
    public static final String ROLE_SERVICE_CATALOG = "ROLE_SERVICE_CATALOG";
    public static final String ROLE_SERVICE_IDENTITY = "ROLE_SERVICE_IDENTITY";

    // MDC Keys
    public static final String MDC_CORRELATION_ID = "correlationId";
}
