package com.qcharge.openadr.service.validation;

import com.qcharge.openadr.service.transport.OpenAdrExchangeContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class OpenAdrExchangeValidationService {

    private final List<OpenAdrExchangeValidator> validators;

    public void validate(OpenAdrExchangeContext<?, ?> context) {
        validators.stream()
                .filter(validator -> validator.supports(context))
                .forEach(validator -> validator.validate(context));
    }
}
