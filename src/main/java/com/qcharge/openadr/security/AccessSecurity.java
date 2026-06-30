package com.qcharge.openadr.security;

import com.qcharge.openadr.feign.FeignInnerAuthenticationRemoteCall;
import feign.Response;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Slf4j
@Component("accessSecurity")
@RequiredArgsConstructor
public class AccessSecurity {
    private final FeignInnerAuthenticationRemoteCall authenticationRemoteCall;

    @Setter @Getter
    private boolean ignoreDSAuth = false;

    public boolean hasValidSuperAdminJWTHeader(@Nullable String receivedValue) {
        if (ignoreDSAuth && StringUtils.isNotBlank(receivedValue)) return true;

        Response response = authenticationRemoteCall.authenticated(receivedValue);
        Integer responseStatus = Objects.nonNull(response) ? response.status() : null;
        log.debug("DS response's status: {}.", responseStatus);

        return Objects.equals(HttpStatus.OK.value(), responseStatus);
    }

}
