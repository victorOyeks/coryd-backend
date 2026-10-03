package org.coryd.ops.dto;

public record DriverSummary(
        String id,
        String ref,
        String name,
        String phone,
        String photoUrl
) {
}
