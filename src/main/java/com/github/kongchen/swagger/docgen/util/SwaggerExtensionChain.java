package com.github.kongchen.swagger.docgen.util;

import io.swagger.annotations.ApiOperation;
import io.swagger.jaxrs.ext.SwaggerExtension;
import io.swagger.jaxrs.ext.SwaggerExtensions;
import io.swagger.models.Operation;
import io.swagger.models.parameters.Parameter;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Chain of {@link SwaggerExtension}s
 */
public class SwaggerExtensionChain {

    private final Iterator<SwaggerExtension> chain = SwaggerExtensions.chain();

    private Optional<SwaggerExtension> findFirst() {
        return Optional.of(chain)
                .filter(Iterator::hasNext)
                .map(Iterator::next);
    }

    public static Optional<String> extractOperationMethod(ApiOperation apiOperation, Method method) {
        SwaggerExtensionChain chain = new SwaggerExtensionChain();
        return chain.findFirst()
                .map(e -> e.extractOperationMethod(apiOperation, method, chain.chain));
    }

    public static Optional<List<Parameter>> extractParameters(List<Annotation> annotations, Type type, Set<Type> typesToSkip) {
        SwaggerExtensionChain chain = new SwaggerExtensionChain();
        return chain.findFirst()
                .map(e -> e.extractParameters(annotations, type, typesToSkip, chain.chain));
    }

    public static void decorateOperation(Operation operation, Method method) {
        SwaggerExtensionChain chain = new SwaggerExtensionChain();
        chain.findFirst()
                .ifPresent(e -> e.decorateOperation(operation, method, chain.chain));
    }
}
