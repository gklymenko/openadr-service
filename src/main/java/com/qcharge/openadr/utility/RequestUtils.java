package com.qcharge.openadr.utility;

import lombok.experimental.UtilityClass;

import java.util.UUID;

@UtilityClass
public class RequestUtils {

    public static String newRequestId() {
        return UUID.randomUUID().toString();
    }

}
