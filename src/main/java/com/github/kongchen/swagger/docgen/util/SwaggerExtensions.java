package com.github.kongchen.swagger.docgen.util;

import io.swagger.jaxrs.ext.SwaggerExtension;

import java.util.Iterator;
import java.util.Optional;

/**
 * Utility functions for {@link SwaggerExtension}s
 */
public class SwaggerExtensions {

    private SwaggerExtensions() {
    }

    public static Iterator<SwaggerExtension> chain() {
        return io.swagger.jaxrs.ext.SwaggerExtensions.chain();
    }

    public static Optional<SwaggerExtension> findFirst(Iterator<SwaggerExtension> chain) {
        return Optional.of(chain)
                .filter(Iterator::hasNext)
                .map(Iterator::next);
    }
}
