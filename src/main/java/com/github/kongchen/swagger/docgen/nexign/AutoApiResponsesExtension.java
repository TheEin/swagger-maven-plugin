package com.github.kongchen.swagger.docgen.nexign;

import com.nexign.swagger.annotations.AutoApiResponses;
import io.swagger.annotations.ApiOperation;
import io.swagger.jaxrs.ext.AbstractSwaggerExtension;
import io.swagger.jaxrs.ext.SwaggerExtension;
import io.swagger.models.Model;
import io.swagger.models.Operation;
import io.swagger.models.RefModel;
import io.swagger.models.Response;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.Optional;

public class AutoApiResponsesExtension extends AbstractSwaggerExtension {

    @Override
    public String extractOperationMethod(ApiOperation apiOperation, Method method, Iterator<SwaggerExtension> chain) {
        RequestMapping requestMapping = AnnotationUtils.findAnnotation(method, RequestMapping.class);
        if (requestMapping != null && requestMapping.method().length == 1) {
            return requestMapping.method()[0].name().toLowerCase();
        }
        for (Annotation annotation : AnnotationUtils.getAnnotations(method)) {
            AnnotationUtils.findAnnotation(method, RequestMapping.class);
        }
        return super.extractOperationMethod(apiOperation, method, chain);
    }

    @Override
    public void decorateOperation(Operation operation, Method method, Iterator<SwaggerExtension> chain) {
        Optional.ofNullable(method.getAnnotation(AutoApiResponses.class))
                .ifPresent(annotation -> generateApiResponses(annotation, operation, method));

        super.decorateOperation(operation, method, chain);
    }

    private void generateApiResponses(AutoApiResponses annotation, Operation operation, Method method) {
        Class<?> returnType = method.getReturnType();
        Model okModel = null;
        if (!returnType.equals(Void.TYPE)) {
            okModel = new RefModel(returnType.getSimpleName());
        }
        Response okResponse = new Response()
                .description("Запрос выполнен успешно")
                .responseSchema(okModel);
        operation.response(HttpStatus.OK.value(), okResponse);
    }
}
