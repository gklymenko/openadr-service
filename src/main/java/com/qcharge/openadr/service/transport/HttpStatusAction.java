package com.qcharge.openadr.service.transport;

/**
 * Action required for an HTTP response or connection failure.
 */
public enum HttpStatusAction {
    ACCEPT,
    DO_NOT_RETRY,
    RETRY_AFTER_QUIESCE
}
