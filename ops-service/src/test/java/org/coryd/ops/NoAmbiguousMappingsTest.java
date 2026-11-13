package org.coryd.ops;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two controllers mapping the same method + path make the service refuse to start ("Ambiguous mapping") - which no
 * sliced test notices. This happened on 2026-11-13 (phase 5 issue reports and phase 6 analytics both under
 * /internal/ops/reports) and failed the dev deploy. Path variables are normalised, so /x/{a} and /x/{b} clash too.
 */
class NoAmbiguousMappingsTest {

    @Test
    void noTwoHandlersShareAMethodAndPath() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Map<String, String> seen = new HashMap<>();
        List<String> clashes = new ArrayList<>();
        for (var candidate : scanner.findCandidateComponents("org.coryd.ops")) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            RequestMapping base = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
            String[] prefixes = base == null || base.path().length == 0 ? new String[]{""} : base.path();
            for (Method method : type.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                String[] paths = mapping.path().length == 0 ? new String[]{""} : mapping.path();
                String[] verbs = mapping.method().length == 0 ? new String[]{"ANY"}
                        : java.util.Arrays.stream(mapping.method()).map(Enum::name).toArray(String[]::new);
                String produces = String.join(",", mapping.produces());
                for (String prefix : prefixes) {
                    for (String path : paths) {
                        String normalised = (prefix + (path.isEmpty() || path.startsWith("/") ? path : "/" + path))
                                .replaceAll("\\{[^}]+}", "{}").replaceAll("/+$", "");
                        for (String verb : verbs) {
                            String key = verb + " " + normalised + " " + produces;
                            String where = type.getSimpleName() + "." + method.getName();
                            String previous = seen.putIfAbsent(key, where);
                            if (previous != null) {
                                clashes.add(key + " -> " + previous + " and " + where);
                            }
                        }
                    }
                }
            }
        }
        assertThat(clashes).as("handlers sharing a method and path").isEmpty();
        assertThat(seen).as("the scan found the controllers").isNotEmpty();
    }
}
