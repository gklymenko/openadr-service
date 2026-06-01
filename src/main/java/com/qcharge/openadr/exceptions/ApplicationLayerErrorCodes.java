package com.qcharge.openadr.exceptions;

/**
 * OpenADR 2.0b application-layer response codes (spec section 10.6 / Rule 48).
 */
public final class ApplicationLayerErrorCodes {

    private ApplicationLayerErrorCodes() {}

    // Success
    public static final int OK = 200;

    // Compliance errors (45x)
    public static final int OUT_OF_SEQUENCE = 450;
    public static final int NOT_ALLOWED = 451;
    public static final int INVALID_ID = 452;
    public static final int NOT_RECOGNIZED = 453;
    public static final int INVALID_DATA = 454;
    public static final int COMPLIANCE_ERROR_OTHER = 459;

    // Deployment errors (46x)
    public static final int SIGNAL_NOT_SUPPORTED = 460;
    public static final int REPORT_NOT_SUPPORTED = 461;
    public static final int TARGET_MISMATCH = 462;
    public static final int NOT_REGISTERED = 463;
    public static final int DEPLOYMENT_ERROR_OTHER = 469;
}