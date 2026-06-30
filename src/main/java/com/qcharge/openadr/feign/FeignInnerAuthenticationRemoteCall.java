package com.qcharge.openadr.feign;

import com.qcharge.openadr.models.constants.Constants;
import feign.Response;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

//TODO:: already used RestClient, no need to use Feign
@FeignClient(name = "data-service-http-client", url = "${data.service.inner.domain}")
public interface FeignInnerAuthenticationRemoteCall {

    @GetMapping("/inner/auth/super-admin")
    Response authenticated(@RequestHeader(name = Constants.AUTHORIZATION_HEADER) String authentication);

}
