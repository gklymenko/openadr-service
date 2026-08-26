package com.qcharge.openadr.service.report.model;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

public final class ReportRidCodec {

    private ReportRidCodec() {
    }

    public static String encode(Collection<String> rids) {
        return String.join(",", rids);
    }

    public static Set<String> decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(encoded.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
