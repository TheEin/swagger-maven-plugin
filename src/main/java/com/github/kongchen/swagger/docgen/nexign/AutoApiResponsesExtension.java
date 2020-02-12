package com.github.kongchen.swagger.docgen.nexign;

import com.nexign.swagger.annotations.AutoApiResponses;
import io.swagger.annotations.ApiOperation;
import io.swagger.jaxrs.ext.AbstractSwaggerExtension;
import io.swagger.jaxrs.ext.SwaggerExtension;
import io.swagger.models.Model;
import io.swagger.models.Operation;
import io.swagger.models.RefModel;
import io.swagger.models.Response;
import io.swagger.models.parameters.Parameter;
import org.springframework.http.HttpStatus;

import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.Optional;

public class AutoApiResponsesExtension extends AbstractSwaggerExtension {

    private static final RefModel ERROR_DESCRIPTION = new RefModel("ErrorDescription");

    private static final String IN_PATH = "path";

    @Override
    public void decorateOperation(Operation operation, Method method, Iterator<SwaggerExtension> chain) {
        Optional.ofNullable(method.getAnnotation(AutoApiResponses.class))
                .ifPresent(annotation -> generateApiResponses(operation, method));

        super.decorateOperation(operation, method, chain);
    }

    private void generateApiResponses(Operation operation, Method method) {
        addOkResponse(operation, method);

        if (!operation.getParameters().isEmpty()) {
            addBadRequestResponse(operation);
        }

        operation.getParameters().stream()
                .map(Parameter::getIn)
                .filter(IN_PATH::equals)
                .findFirst()
                .ifPresent(s ->
                        addNotFoundResponse(operation));

        addForbiddenResponse(operation);
        addGenericErrorResponse(operation);
    }

    private void addOkResponse(Operation operation, Method method) {
        int httpCode = Optional.ofNullable(
                method.getAnnotation(ApiOperation.class))
                .map(ApiOperation::code)
                .orElse(HttpStatus.OK.value());

        Class<?> returnType = method.getReturnType();
        Model okModel = null;
        if (!returnType.equals(Void.TYPE)) {
            okModel = new RefModel(returnType.getSimpleName());
        }

        operation.response(httpCode, new Response()
                .description("Запрос выполнен успешно")
                .responseSchema(okModel));
    }

    private void addBadRequestResponse(Operation operation) {
        operation.response(HttpStatus.BAD_REQUEST.value(), new Response()
                .description("Некорректный запрос")
                .responseSchema(ERROR_DESCRIPTION));
    }

    private void addNotFoundResponse(Operation operation) {
        operation.response(HttpStatus.NOT_FOUND.value(), new Response()
                .description("Не найдена запись")
                .responseSchema(ERROR_DESCRIPTION));
    }

    private void addForbiddenResponse(Operation operation) {
        operation.response(HttpStatus.FORBIDDEN.value(), new Response()
                .description("Отсутствуют права доступа на метод")
                .responseSchema(ERROR_DESCRIPTION));
    }

    private void addGenericErrorResponse(Operation operation) {
        operation.response(HttpStatus.INTERNAL_SERVER_ERROR.value(), new Response()
                .description("Ошибка выполнения запроса")
                .responseSchema(ERROR_DESCRIPTION));
    }
}
