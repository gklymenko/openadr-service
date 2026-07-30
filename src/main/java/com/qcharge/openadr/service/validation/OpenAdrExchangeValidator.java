package com.qcharge.openadr.service.validation;

import com.qcharge.openadr.service.transport.OpenAdrExchangeContext;

/**
 * Validates one request/response exchange after XML/schema and response-type
 * validation have completed.
 */
public interface OpenAdrExchangeValidator {

    boolean supports(OpenAdrExchangeContext<?, ?> context);

    void validate(OpenAdrExchangeContext<?, ?> context);
}
