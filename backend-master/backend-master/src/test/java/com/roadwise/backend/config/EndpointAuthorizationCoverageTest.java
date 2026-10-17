package com.roadwise.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.util.AntPathMatcher;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fails the build when an endpoint exists that no route rule names.
 *
 * <p>{@link SecurityConfig} ends in {@code denyAll()}, so an unmapped controller is not a
 * security hole -- it is a 403 nobody can explain. This test turns that into a build failure
 * at the moment the endpoint is added, which is the same trade as
 * {@code FlywaySchemaDriftTest}: make the omission loud at CI rather than at 2am.
 *
 * <p>Scans the controller package by reading class metadata rather than by loading a Spring
 * context, so it needs no database, no broker and no web server.
 */
class EndpointAuthorizationCoverageTest {

    private static final String CONTROLLER_PACKAGE = "classpath*:com/roadwise/backend/controller/**/*.class";

    private final AntPathMatcher matcher = new AntPathMatcher();

    @Test
    void everyControllerEndpointIsCoveredByARouteRule() throws Exception {
        List<String> uncovered = new ArrayList<>();
        int checked = 0;

        for (Class<?> controller : controllers()) {
            String[] prefixes = pathsOf(AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class));

            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                for (String prefix : prefixes) {
                    for (String path : pathsOf(mapping)) {
                        String endpoint = join(prefix, path);
                        for (RequestMethod verb : verbsOf(mapping)) {
                            checked++;
                            if (!covered(verb, endpoint)) {
                                uncovered.add(verb + " " + endpoint + "  (" + controller.getSimpleName() + ")");
                            }
                        }
                    }
                }
            }
        }

        assertTrue(checked > 20, "the scan found only " + checked + " endpoint(s); it is not seeing the controllers");
        assertTrue(uncovered.isEmpty(),
                "no route rule covers these endpoints, so SecurityConfig.denyAll() refuses them:\n  "
                        + String.join("\n  ", uncovered));
    }

    private List<Class<?>> controllers() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        CachingMetadataReaderFactory factory = new CachingMetadataReaderFactory();

        List<Class<?>> controllers = new ArrayList<>();
        for (Resource resource : resolver.getResources(CONTROLLER_PACKAGE)) {
            MetadataReader reader = factory.getMetadataReader(resource);
            if (reader.getAnnotationMetadata().hasAnnotation(RestController.class.getName())) {
                controllers.add(Class.forName(reader.getClassMetadata().getClassName()));
            }
        }
        assertTrue(!controllers.isEmpty(), "no @RestController classes found under " + CONTROLLER_PACKAGE);
        return controllers;
    }

    private boolean covered(RequestMethod verb, String endpoint) {
        if (verb == RequestMethod.GET && matchesAny(SecurityConfig.PUBLIC_GET_PATHS, endpoint)) {
            return true;
        }
        return matchesAny(SecurityConfig.PUBLIC_PATHS, endpoint)
                || matchesAny(SecurityConfig.STEP_UP_PATHS, endpoint)
                || matchesAny(SecurityConfig.AUTHENTICATED_PREFIXES, endpoint);
    }

    private boolean matchesAny(List<String> patterns, String endpoint) {
        return patterns.stream().anyMatch(pattern -> matcher.match(pattern, endpoint));
    }

    private static String[] pathsOf(RequestMapping mapping) {
        if (mapping == null) {
            return new String[]{""};
        }
        String[] declared = mapping.value();
        return declared.length == 0 ? new String[]{""} : declared;
    }

    private static RequestMethod[] verbsOf(RequestMapping mapping) {
        return mapping.method().length == 0 ? RequestMethod.values() : mapping.method();
    }

    private static String join(String prefix, String path) {
        String joined = "/" + prefix + "/" + path;
        return joined.replaceAll("/{2,}", "/").replaceAll("(.)/$", "$1");
    }
}
