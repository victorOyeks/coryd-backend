package org.coryd.ops.platform;

import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/** Query parameters with the nulls left out. */
final class Params {

    private final MultiValueMap<String, String> map = new LinkedMultiValueMap<>();

    static Params of() {
        return new Params();
    }

    Params put(String name, Object value) {
        if (value != null) {
            if (value instanceof Iterable<?> values) {
                values.forEach(v -> map.add(name, String.valueOf(v)));
            } else {
                map.add(name, String.valueOf(value));
            }
        }
        return this;
    }

    MultiValueMap<String, String> build() {
        return map;
    }
}
