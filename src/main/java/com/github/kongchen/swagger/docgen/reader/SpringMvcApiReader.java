package com.github.kongchen.swagger.docgen.reader;

import com.github.kongchen.swagger.docgen.GenerateException;
import com.github.kongchen.swagger.docgen.spring.SpringResource;
import com.github.kongchen.swagger.docgen.util.SpringUtils;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponses;
import io.swagger.annotations.Authorization;
import io.swagger.annotations.AuthorizationScope;
import io.swagger.converter.ModelConverters;
import io.swagger.models.Model;
import io.swagger.models.Operation;
import io.swagger.models.Response;
import io.swagger.models.SecurityRequirement;
import io.swagger.models.Swagger;
import io.swagger.models.parameters.Parameter;
import io.swagger.models.properties.Property;
import io.swagger.models.properties.RefProperty;
import io.swagger.util.BaseReaderUtils;
import org.apache.maven.plugin.logging.Log;
import org.codehaus.plexus.util.StringUtils;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.apache.commons.lang3.StringUtils.defaultIfEmpty;
import static org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation;
import static org.springframework.core.annotation.AnnotationUtils.findAnnotation;

public class SpringMvcApiReader extends AbstractReader<SpringResource> {

    private static final ResponseContainerConverter RESPONSE_CONTAINER_CONVERTER = new ResponseContainerConverter();

    private final SpringExceptionHandlerReader exceptionHandlerReader;

    private String resourcePath;

    public SpringMvcApiReader(Swagger swagger, Log log) {
        super(swagger, log);
        exceptionHandlerReader = new SpringExceptionHandlerReader(log);
    }

    @Override
    public void read(Set<Class<?>> classes) throws GenerateException {
        //relate all methods to one base request mapping if multiple controllers exist for that mapping
        //get all methods from each controller & find their request mapping
        //create map - resource string (after first slash) as key, new SpringResource as value
        Map<String, SpringResource> resourceMap = generateResourceMap(classes);
        exceptionHandlerReader.processExceptionHandlers(classes);
        for (SpringResource resource : resourceMap.values()) {
            try {
                read(new ResourceContext<>(resource));
            } catch (Exception e) {
                throw new RuntimeException("Failed to read Swagger specs for resource " + resource, e);
            }
        }
    }

    public void read(ResourceContext<SpringResource> ctx) {
        List<Method> methods = ctx.resource.getMethods();

        // Add the description from the controller api
        Class<?> controller = ctx.resource.getControllerClass();
        RequestMapping controllerRM = findMergedAnnotation(controller, RequestMapping.class);

        String[] controllerProduces;
        String[] controllerConsumes;
        if (controllerRM == null) {
            controllerProduces = new String[0];
            controllerConsumes = new String[0];
        } else {
            controllerProduces = controllerRM.produces();
            controllerConsumes = controllerRM.consumes();
        }

        if (controller.isAnnotationPresent(Api.class)) {
            ctx.api = findMergedAnnotation(controller, Api.class);
            if (!canReadApi(ctx)) {
                return;
            }
            updateTagsForApi(ctx);
            getSecurityRequirements(ctx);
        }

        resourcePath = ctx.resource.getControllerMapping();

        //collect api from method with @RequestMapping
        Map<String, List<Method>> apiMethodMap = collectApisByRequestMapping(methods);

        for (String path : apiMethodMap.keySet()) {
            for (Method method : apiMethodMap.get(path)) {
                OperationContext<SpringResource> op = new OperationContext<>(ctx);
                op.path = path;
                op.method = method;
                try {
                    readOperation(op, controllerProduces, controllerConsumes);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to read Swagger specs for method " + method, e);
                }
            }
        }
    }

    protected void readOperation(OperationContext<SpringResource> op, String[] controllerProduces, String[] controllerConsumes) {
        RequestMapping requestMapping = findMergedAnnotation(op.method, RequestMapping.class);
        if (requestMapping == null) {
            return;
        }
        op.api = findMergedAnnotation(op.method, ApiOperation.class);
        if (op.ctx.api != null && op.ctx.api.hidden()) {
            return;
        }

        Map<String, String> regexMap = new HashMap<>();
        op.path = parseOperationPath(op.path, regexMap);

        //http method
        for (RequestMethod requestMethod : requestMapping.method()) {
            op.httpMethod = requestMethod.toString().toLowerCase();
            op.operation = parseMethod(op.method, requestMethod);

            if (op.operation == null) {
                continue;
            }
            updateOperationParameters(op, regexMap);

            updateOperationProtocols(op);

            op.produces = requestMapping.produces();
            op.consumes = requestMapping.consumes();

            op.produces = (op.produces.length == 0) ? controllerProduces : op.produces;
            op.consumes = (op.consumes.length == 0) ? controllerConsumes : op.consumes;

            updateOperationConsumes(op);
            updateOperationProduces(op);

            updateTagsForOperation(op);
            updateOperation(op);
            updatePath(op);
        }
    }

    private Operation parseMethod(Method method, RequestMethod requestMethod) {
        int responseCode = 200;
        Operation operation = new Operation();

        RequestMapping requestMapping = findMergedAnnotation(method, RequestMapping.class);
        Type responseClass = null;
        List<String> produces = new ArrayList<>();
        List<String> consumes = new ArrayList<>();
        String responseContainer = null;
        String operationId = getOperationId(method, requestMethod.name());
        Map<String, Property> defaultResponseHeaders = null;

        ApiOperation apiOperation = findMergedAnnotation(method, ApiOperation.class);

        if (apiOperation != null) {
            if (apiOperation.hidden()) {
                return null;
            }
            if (!apiOperation.nickname().isEmpty()) {
                operationId = apiOperation.nickname();
            }

            defaultResponseHeaders = parseResponseHeaders(apiOperation.responseHeaders());

            operation.summary(apiOperation.value()).description(apiOperation.notes());

            Map<String, Object> customExtensions = BaseReaderUtils.parseExtensions(apiOperation.extensions());
            operation.setVendorExtensions(customExtensions);

            if (!apiOperation.response().equals(Void.class)) {
                responseClass = apiOperation.response();
            }
            if (!apiOperation.responseContainer().isEmpty()) {
                responseContainer = apiOperation.responseContainer();
            }

            ///security
            List<SecurityRequirement> securities = new ArrayList<>();
            for (Authorization auth : apiOperation.authorizations()) {
                if (!auth.value().isEmpty()) {
                    SecurityRequirement security = new SecurityRequirement();
                    security.setName(auth.value());
                    for (AuthorizationScope scope : auth.scopes()) {
                        if (!scope.scope().isEmpty()) {
                            security.addScope(scope.scope());
                        }
                    }
                    securities.add(security);
                }
            }
            for (SecurityRequirement sec : securities) {
                operation.security(sec);
            }

            responseCode = apiOperation.code();
        }

        if (responseClass == null) {
            // pick out response from method declaration
            log.info("picking up response class from method " + method);
            responseClass = method.getGenericReturnType();
        }
        if (responseClass instanceof ParameterizedType && ResponseEntity.class.equals(((ParameterizedType) responseClass).getRawType())) {
            responseClass = ((ParameterizedType) responseClass).getActualTypeArguments()[0];
        }
        boolean hasApiAnnotation = false;
        if (responseClass instanceof Class) {
            hasApiAnnotation = findAnnotation((Class<?>) responseClass, Api.class) != null;
        }
        if (responseClass != null
                && !responseClass.equals(Void.class)
                && !responseClass.equals(ResponseEntity.class)
                && !hasApiAnnotation) {
            if (isPrimitive(responseClass)) {
                Property property = ModelConverters.getInstance().readAsProperty(responseClass);
                if (property != null) {
                    Property responseProperty = RESPONSE_CONTAINER_CONVERTER.withResponseContainer(responseContainer, property);
                    operation.response(responseCode, new Response()
                            .description(SUCCESSFUL_OPERATION)
                            .schema(responseProperty)
                            .headers(defaultResponseHeaders));
                }
            } else if (!responseClass.equals(Void.class) && !responseClass.equals(void.class)) {
                Map<String, Model> models = ModelConverters.getInstance().read(responseClass);
                if (models.isEmpty()) {
                    Property pp = ModelConverters.getInstance().readAsProperty(responseClass);
                    operation.response(responseCode, new Response()
                            .description(SUCCESSFUL_OPERATION)
                            .schema(pp)
                            .headers(defaultResponseHeaders));
                }
                for (String key : models.keySet()) {
                    Property responseProperty = RESPONSE_CONTAINER_CONVERTER.withResponseContainer(responseContainer, new RefProperty().asDefault(key));
                    operation.response(responseCode, new Response()
                            .description(SUCCESSFUL_OPERATION)
                            .schema(responseProperty)
                            .headers(defaultResponseHeaders));
                    swagger.model(key, models.get(key));
                }
            }
            Map<String, Model> models = ModelConverters.getInstance().readAll(responseClass);
            for (Map.Entry<String, Model> entry : models.entrySet()) {
                swagger.model(entry.getKey(), entry.getValue());
            }
        }

        operation.operationId(operationId);

        for (String str : requestMapping.produces()) {
            if (!produces.contains(str)) {
                produces.add(str);
            }
        }
        for (String str : requestMapping.consumes()) {
            if (!consumes.contains(str)) {
                consumes.add(str);
            }
        }

        ApiResponses responseAnnotation = findMergedAnnotation(method, ApiResponses.class);
        if (responseAnnotation != null) {
            updateApiResponse(operation, responseAnnotation);
        } else {
            ResponseStatus responseStatus = findMergedAnnotation(method, ResponseStatus.class);
            if (responseStatus != null) {
                updateResponseStatus(operation, responseStatus);
            }
        }

        List<ResponseStatus> errorResponses = exceptionHandlerReader.getResponseStatusesFromExceptions(method);
        for (ResponseStatus responseStatus : errorResponses) {
            int code = responseStatus.code().value();
            String description = defaultIfEmpty(responseStatus.reason(), responseStatus.code().getReasonPhrase());
            operation.response(code, new Response().description(description));
        }

        overrideResponseMessages(operation);

        Deprecated annotation = findAnnotation(method, Deprecated.class);
        if (annotation != null) {
            operation.deprecated(true);
        }

        // process parameters
        Class<?>[] parameterTypes = method.getParameterTypes();
        Type[] genericParameterTypes = method.getGenericParameterTypes();
        Annotation[][] paramAnnotations = method.getParameterAnnotations();
        DefaultParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();
        String[] parameterNames = parameterNameDiscoverer.getParameterNames(method);
        // paramTypes = method.getParameterTypes
        // genericParamTypes = method.getGenericParameterTypes
        for (int i = 0; i < parameterTypes.length; i++) {
            Type type = genericParameterTypes[i];
            List<Annotation> annotations = Arrays.asList(paramAnnotations[i]);
            List<Parameter> parameters = getParameters(type, annotations);

            for (Parameter parameter : parameters) {
                if (parameter.getName().isEmpty()) {
                    parameter.setName(parameterNames[i]);
                }
                operation.parameter(parameter);
            }
        }

        // Process @ApiImplicitParams
        readImplicitParameters(method, operation);

        processOperationDecorator(operation, method);

        addImplicitResponses(operation);

        return operation;
    }

    private void updateResponseStatus(Operation operation, ResponseStatus responseStatus) {
        int code = responseStatus.value().value();
        String reason = responseStatus.reason();

        if (operation.getResponses() != null && operation.getResponses().size() == 1) {
            String currentKey = operation.getResponses().keySet().iterator().next();
            Response oldResponse = operation.getResponses().remove(currentKey);
            if (StringUtils.isNotEmpty(reason)) {
                oldResponse.setDescription(reason);
            }
            operation.response(code, oldResponse);
        } else {
            operation.response(code, new Response().description(reason));
        }
    }

    private Map<String, List<Method>> collectApisByRequestMapping(List<Method> methods) {
        Map<String, List<Method>> apiMethodMap = new HashMap<>();
        for (Method method : methods) {
            RequestMapping requestMapping = findMergedAnnotation(method, RequestMapping.class);
            if (requestMapping != null) {
                String path;
                if (requestMapping.value().length != 0) {
                    path = generateFullPath(requestMapping.value()[0]);
                } else {
                    path = resourcePath;
                }
                if (apiMethodMap.containsKey(path)) {
                    apiMethodMap.get(path).add(method);
                } else {
                    List<Method> ms = new ArrayList<>();
                    ms.add(method);
                    apiMethodMap.put(path, ms);
                }
            }
        }

        return apiMethodMap;
    }

    private String generateFullPath(String path) {
        if (StringUtils.isNotEmpty(path)) {
            return this.resourcePath + (path.startsWith("/") ? path : '/' + path);
        } else {
            return this.resourcePath;
        }
    }

    //Helper method for loadDocuments()
    private Map<String, SpringResource> analyzeController(Class<?> controllerClazz, Map<String, SpringResource> resourceMap, String description) {
        String[] controllerRequestMappingValues = SpringUtils.getControllerResquestMapping(controllerClazz);

        // Iterate over all value attributes of the class-level RequestMapping annotation
        for (String controllerRequestMappingValue : controllerRequestMappingValues) {
            for (Method method : controllerClazz.getMethods()) {
                // Skip methods introduced by compiler
                if (method.isSynthetic()) {
                    continue;
                }
                RequestMapping methodRequestMapping = findMergedAnnotation(method, RequestMapping.class);

                // Look for method-level @RequestMapping annotation
                if (methodRequestMapping != null) {
                    RequestMethod[] requestMappingRequestMethods = methodRequestMapping.method();

                    // For each method-level @RequestMapping annotation, iterate over HTTP Verb
                    for (RequestMethod requestMappingRequestMethod : requestMappingRequestMethods) {
                        String[] methodRequestMappingValues = methodRequestMapping.value();

                        // Check for cases where method-level @RequestMapping#value is not set, and use the controllers @RequestMapping
                        if (methodRequestMappingValues.length == 0) {
                            // The map key is a concat of the following:
                            //   1. The controller package
                            //   2. The controller class name
                            //   3. The controller-level @RequestMapping#value
                            String resourceKey = controllerClazz.getCanonicalName() + controllerRequestMappingValue + requestMappingRequestMethod;
                            if (!resourceMap.containsKey(resourceKey)) {
                                resourceMap.put(
                                        resourceKey,
                                        new SpringResource(controllerClazz, controllerRequestMappingValue, resourceKey, description));
                            }
                            resourceMap.get(resourceKey).addMethod(method);
                        } else {
                            // Here we know that method-level @RequestMapping#value is populated, so
                            // iterate over all the @RequestMapping#value attributes, and add them to the resource map.
                            for (String methodRequestMappingValue : methodRequestMappingValues) {
                                String resourceKey = controllerClazz.getCanonicalName() + controllerRequestMappingValue
                                        + methodRequestMappingValue + requestMappingRequestMethod;
                                if (!(controllerRequestMappingValue + methodRequestMappingValue).isEmpty()) {
                                    if (!resourceMap.containsKey(resourceKey)) {
                                        resourceMap.put(resourceKey, new SpringResource(controllerClazz, methodRequestMappingValue, resourceKey, description));
                                    }
                                    resourceMap.get(resourceKey).addMethod(method);
                                }
                            }
                        }
                    }
                }
            }
        }
        controllerClazz.getFields();
        controllerClazz.getDeclaredFields(); //<--In case developer declares a field without an associated getter/setter.
        //this will allow NoClassDefFoundError to be caught before it triggers bamboo failure.

        return resourceMap;
    }

    protected Map<String, SpringResource> generateResourceMap(Set<Class<?>> validClasses) throws GenerateException {
        Map<String, SpringResource> resourceMap = new HashMap<>();
        for (Class<?> aClass : validClasses) {
            //This try/catch block is to stop a bamboo build from failing due to NoClassDefFoundError
            //This occurs when a class or method loaded by reflections contains a type that has no dependency
            try {
                resourceMap = analyzeController(aClass, resourceMap, "");
                List<Method> mList = new ArrayList<>(Arrays.asList(aClass.getMethods()));
                if (aClass.getSuperclass() != null) {
                    mList.addAll(Arrays.asList(aClass.getSuperclass().getMethods()));
                }
            } catch (NoClassDefFoundError e) {
                log.error(e.getMessage());
                log.info(aClass.getName());
                //exception occurs when a method type or annotation is not recognized by the plugin
            }
        }

        return resourceMap;
    }
}
