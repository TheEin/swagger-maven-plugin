package com.github.kongchen.swagger.docgen.reader;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeInfo.As;
import com.github.kongchen.swagger.docgen.util.SwaggerExtensionChain;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponses;
import io.swagger.annotations.Authorization;
import io.swagger.annotations.AuthorizationScope;
import io.swagger.annotations.SwaggerDefinition;
import io.swagger.converter.ModelConverters;
import io.swagger.models.ArrayModel;
import io.swagger.models.Model;
import io.swagger.models.ModelImpl;
import io.swagger.models.Operation;
import io.swagger.models.Response;
import io.swagger.models.SecurityRequirement;
import io.swagger.models.Swagger;
import io.swagger.models.Tag;
import io.swagger.models.parameters.BodyParameter;
import io.swagger.models.parameters.Parameter;
import io.swagger.models.parameters.RefParameter;
import io.swagger.models.properties.Property;
import io.swagger.models.properties.RefProperty;
import io.swagger.models.properties.StringProperty;
import io.swagger.models.refs.RefType;
import io.swagger.util.BaseReaderUtils;
import io.swagger.util.ReflectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.plugin.logging.Log;
import org.reflections.Reflections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.web.bind.annotation.RequestMapping;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.HEAD;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.OPTIONS;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

public class JaxrsReader extends AbstractReader<Class<?>> {

    private static final Logger LOGGER = LoggerFactory.getLogger(JaxrsReader.class);

    private static final ResponseContainerConverter RESPONSE_CONTAINER_CONVERTER = new ResponseContainerConverter();

    private Map<String, Tag> discoveredTags;

    public JaxrsReader(Swagger swagger, Log log) {
        super(swagger, log);
    }

    @Override
    public void read(Set<Class<?>> classes) {
        for (Class<?> cls : classes) {
            try {
                read(cls);
            } catch (Exception e) {
                throw new RuntimeException("Failed to read Swagger specs for class " + cls, e);
            }
        }
    }

    public void read(Class<?> cls) {
        read(new ResourceContext<>(cls));
    }

    protected String resolveApiPath(Class<?> cls) {
        return Optional.ofNullable(AnnotationUtils.findAnnotation(cls, Path.class))
                .map(Path::value)
                .orElseGet(() -> Optional.ofNullable(AnnotationUtils.findAnnotation(cls, RequestMapping.class))
                        .map(RequestMapping::path)
                        .flatMap(paths -> Arrays.stream(paths).findFirst())
                        .orElse(null));
    }

    protected String resolveMethodPath(Method method) {
        return Optional.ofNullable(AnnotationUtils.findAnnotation(method, Path.class))
                .map(Path::value)
                .orElseGet(() -> findRequestMappingPath(method));
    }

    protected String findRequestMappingPath(Method method) {
        for (Annotation annotation : AnnotationUtils.getAnnotations(method)) {
            Object path = AnnotationUtils.getValue(annotation, "path");
            if (path != null) {
                Class<?> cls = path.getClass();
                if (cls.isArray()) {
                    if (Array.getLength(path) == 0) {
                        continue;
                    }
                    Object p = Array.get(path, 0);
                    if (p == null) {
                        continue;
                    }
                    path = p;
                }
                return String.valueOf(path);
            }
        }
        return null;
    }

    protected void read(ResourceContext<Class<?>> ctx) {
        ctx.api = AnnotationUtils.findAnnotation(ctx.resource, Api.class);
        ctx.path = resolveApiPath(ctx.resource);

        // only read if allowing hidden apis OR api is not marked as hidden
        if (!canReadApi(ctx)) {
            return;
        }

        updateTagsForApi(ctx);
        getSecurityRequirements(ctx);
        scanClasspathForTags();

        // merge consumes, produces

        List<Method> filteredMethods = getFilteredMethods(ctx.resource);
        readCommonParameters(ctx.resource, filteredMethods);

        // look for method-level annotated properties

        // handle subresources by looking at return type

        // parse the method
        for (Method method : filteredMethods) {
            OperationContext<Class<?>> op = new OperationContext<>(ctx);
            op.method = method;
            try {
                readOperation(op);
            } catch (Exception e) {
                throw new RuntimeException("Failed to read Swagger specs for method " + method, e);
            }
        }
    }

    protected void readOperation(OperationContext<Class<?>> op) {
        op.api = AnnotationUtils.findAnnotation(op.method, ApiOperation.class);
        if (op.api != null && op.api.hidden()) {
            return;
        }
        String methodPath = resolveMethodPath(op.method);

        //is method default handler within a subresource
        String parentPath = op.ctx.parentPath;
        if (op.ctx.path == null && methodPath == null && parentPath != null && op.ctx.readHidden) {
            methodPath = op.ctx.parentPath;
            parentPath = null;
        }
        op.path = getPath(op.ctx.path, methodPath, parentPath);
        if (op.path != null) {
            Map<String, String> regexMap = new HashMap<>();
            op.path = parseOperationPath(op.path, regexMap);

            op.httpMethod = extractOperationMethod(op.api, op.method);

            parseMethod(op);
            updateOperationParameters(op, regexMap);
            updateOperationProtocols(op);


            Consumes consumes = AnnotationUtils.findAnnotation(op.ctx.resource, Consumes.class);
            if (consumes != null) {
                op.consumes = consumes.value();
            }
            Produces produces = AnnotationUtils.findAnnotation(op.ctx.resource, Produces.class);
            if (produces != null) {
                op.produces = produces.value();
            }

            updateOperationConsumes(op);
            updateOperationProduces(op);

            handleSubResource(op);

            // can't continue without a valid http method
            op.httpMethod = (op.httpMethod == null) ? op.ctx.parentMethod : op.httpMethod;
            updateTagsForOperation(op);
            updateOperation(op);
            updatePath(op);
        }
        updateTagDescriptions();
    }

    private List<Method> getFilteredMethods(Class<?> cls) {
        Method[] methods = cls.getMethods();
        List<Method> filteredMethods = new ArrayList<>();
        for (Method method : methods) {
            if (!method.isBridge()) {
                filteredMethods.add(method);
            }
        }
        return filteredMethods;
    }

    /**
     * Returns true when the swagger object already contains a common parameter
     * with the same name and type as the passed parameter.
     *
     * @param parameter The parameter to check.
     * @return true if the swagger object already contains a common parameter with the same name and type
     */
    private boolean hasCommonParameter(Parameter parameter) {
        Parameter commonParameter = swagger.getParameter(parameter.getName());
        return commonParameter != null && parameter.getIn().equals(commonParameter.getIn());
    }

    private void readCommonParameters(Class<?> cls, List<Method> filteredMethods) {
        Path path = AnnotationUtils.findAnnotation(cls, Path.class);
        if (path != null) {
            return;
        }

        for (Method method : filteredMethods) {
            path = AnnotationUtils.findAnnotation(method, Path.class);
            if (path != null) {
                return;
            }

            String httpMethod = extractOperationMethod(null, method);
            if (httpMethod != null) {
                return;
            }
        }

        Field[] fields = cls.getDeclaredFields();
        for (Field field : fields) {
            Annotation[] annotations = field.getAnnotations();
            if (annotations.length > 0) {
                List<Parameter> params = getParameters(cls, Arrays.asList(annotations));
                for (Parameter param : params) {
                    if (hasCommonParameter(param)) {
                        String msg = "[" + cls.getCanonicalName() + "] Redefining common parameter '" + param.getName()
                                + "' already defined elsewhere";
                        throw new RuntimeException(msg);
                    }
                    swagger.addParameter(param.getName(), param);
                }
            }
        }
    }

    private void updateTagDescriptions() {
        if (swagger.getTags() != null) {
            for (Tag tag : swagger.getTags()) {
                Tag rightTag = discoveredTags.get(tag.getName());
                if (rightTag != null && rightTag.getDescription() != null) {
                    tag.setDescription(rightTag.getDescription());
                }
            }
        }
    }

    private void scanClasspathForTags() {
        discoveredTags = new HashMap<>();
        for (Class<?> aClass : new Reflections("").getTypesAnnotatedWith(SwaggerDefinition.class)) {
            SwaggerDefinition swaggerDefinition = AnnotationUtils.findAnnotation(aClass, SwaggerDefinition.class);

            for (io.swagger.annotations.Tag tag : swaggerDefinition.tags()) {

                String tagName = tag.name();
                if (!tagName.isEmpty()) {
                    discoveredTags.put(tag.name(), new Tag().name(tag.name()).description(tag.description()));
                }
            }
        }
    }

    private void handleSubResource(OperationContext<Class<?>> parent) {
        if (isSubResource(parent.httpMethod, parent.method)) {
            Class<?> responseClass = parent.method.getReturnType();
            if (parent.api != null
                    && !parent.api.response().equals(Void.class)
                    && !parent.api.response().equals(void.class)) {
                responseClass = parent.api.response();
            }
            ResourceContext ctx = new ResourceContext(parent, responseClass);
            LOGGER.debug("handling sub-resource method {} -> {}", parent.method, parent.ctx.resource);
            read(ctx);
        }
    }

    protected boolean isSubResource(String httpMethod, Method method) {
        Class<?> responseClass = method.getReturnType();
        return (responseClass != null) && (httpMethod == null) && (AnnotationUtils.findAnnotation(method, Path.class) != null);
    }

    private String getPath(String classLevelPath, String methodLevelPath, String parentPath) {
        if (classLevelPath == null && methodLevelPath == null) {
            return null;
        }
        StringBuilder stringBuilder = new StringBuilder();
        if (parentPath != null && !parentPath.isEmpty() && !parentPath.equals("/")) {
            if (!parentPath.startsWith("/")) {
                parentPath = "/" + parentPath;
            }
            if (parentPath.endsWith("/")) {
                parentPath = parentPath.substring(0, parentPath.length() - 1);
            }

            stringBuilder.append(parentPath);
        }
        if (classLevelPath != null) {
            stringBuilder.append(classLevelPath);
        }
        if (methodLevelPath != null && !methodLevelPath.equals("/")) {
            String methodPath = methodLevelPath;
            if (!methodPath.startsWith("/") && !stringBuilder.toString().endsWith("/")) {
                stringBuilder.append("/");
            }
            if (methodPath.endsWith("/")) {
                methodPath = methodPath.substring(0, methodPath.length() - 1);
            }
            stringBuilder.append(methodPath);
        }
        String output = stringBuilder.toString();
        if (!output.startsWith("/")) {
            output = "/" + output;
        }
        if (output.endsWith("/") && output.length() > 1) {
            return output.substring(0, output.length() - 1);
        } else {
            return output;
        }
    }

    private void parseMethod(OperationContext<Class<?>> op) {
        int responseCode = 200;
        ApiOperation apiOperation = AnnotationUtils.findAnnotation(op.method, ApiOperation.class);

        String operationId = getOperationId(op.method, op.httpMethod);

        String responseContainer = null;

        Type responseClassType = null;
        Map<String, Property> defaultResponseHeaders = null;

        if (apiOperation != null) {
            if (apiOperation.hidden()) {
                return;
            }
            if (!apiOperation.nickname().isEmpty()) {
                operationId = apiOperation.nickname();
            }

            defaultResponseHeaders = parseResponseHeaders(apiOperation.responseHeaders());
            op.operation.summary(apiOperation.value()).description(apiOperation.notes());

            Map<String, Object> customExtensions = BaseReaderUtils.parseExtensions(apiOperation.extensions());
            op.operation.setVendorExtensions(customExtensions);

            if (!apiOperation.response().equals(Void.class) && !apiOperation.response().equals(void.class)) {
                responseClassType = apiOperation.response();
            }
            if (!apiOperation.responseContainer().isEmpty()) {
                responseContainer = apiOperation.responseContainer();
            }
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
                op.operation.security(sec);
            }
        }
        op.operation.operationId(operationId);

        if (responseClassType == null) {
            // pick out response from method declaration
            LOGGER.debug("picking up response class from method " + op.method);
            responseClassType = op.method.getGenericReturnType();
        }
        boolean hasApiAnnotation = false;
        if (responseClassType instanceof Class) {
            hasApiAnnotation = AnnotationUtils.findAnnotation((Class) responseClassType, Api.class) != null;
        }
        if ((responseClassType != null)
                && !responseClassType.equals(Void.class)
                && !responseClassType.equals(void.class)
                && !responseClassType.equals(javax.ws.rs.core.Response.class)
                && !hasApiAnnotation
                && !isSubResource(op.httpMethod, op.method)) {
            if (isPrimitive(responseClassType)) {
                Property property = ModelConverters.getInstance().readAsProperty(responseClassType);
                if (property != null) {
                    Property responseProperty = RESPONSE_CONTAINER_CONVERTER.withResponseContainer(responseContainer, property);

                    op.operation.response(responseCode, new Response()
                            .description("successful operation")
                            .schema(responseProperty)
                            .headers(defaultResponseHeaders));
                }
            } else if (!responseClassType.equals(Void.class) && !responseClassType.equals(void.class)) {
                Map<String, Model> models = ModelConverters.getInstance().read(responseClassType);
                if (models.isEmpty()) {
                    Property p = ModelConverters.getInstance().readAsProperty(responseClassType);
                    op.operation.response(responseCode, new Response()
                            .description("successful operation")
                            .schema(p)
                            .headers(defaultResponseHeaders));
                }
                for (String key : models.keySet()) {
                    Property responseProperty = RESPONSE_CONTAINER_CONVERTER.withResponseContainer(responseContainer, new RefProperty().asDefault(key));


                    op.operation.response(responseCode, new Response()
                            .description("successful operation")
                            .schema(responseProperty)
                            .headers(defaultResponseHeaders));
                    swagger.model(key, models.get(key));
                }
            }
            Map<String, Model> models = readAllModels(responseClassType);
            for (Map.Entry<String, Model> entry : models.entrySet()) {
                swagger.model(entry.getKey(), entry.getValue());
            }
        }

        Consumes consumes = AnnotationUtils.findAnnotation(op.method, Consumes.class);
        if (consumes != null) {
            for (String mediaType : consumes.value()) {
                op.operation.consumes(mediaType);
            }
        }

        Produces produces = AnnotationUtils.findAnnotation(op.method, Produces.class);
        if (produces != null) {
            for (String mediaType : produces.value()) {
                op.operation.produces(mediaType);
            }
        }

        ApiResponses responseAnnotation = AnnotationUtils.findAnnotation(op.method, ApiResponses.class);
        if (responseAnnotation != null) {
            updateApiResponse(op.operation, responseAnnotation);
        }

        overrideResponseMessages(op.operation);

        if (AnnotationUtils.findAnnotation(op.method, Deprecated.class) != null) {
            op.operation.deprecated(true);
        }

        // process parameters
        Class<?>[] parameterTypes = op.method.getParameterTypes();
        Type[] genericParameterTypes = op.method.getGenericParameterTypes();
        Annotation[][] paramAnnotations = findParamAnnotations(op.method);

        for (int i = 0; i < parameterTypes.length; i++) {
            Type type = genericParameterTypes[i];
            List<Annotation> annotations = Arrays.asList(paramAnnotations[i]);
            List<Parameter> parameters = getParameters(type, annotations);

            for (Parameter parameter : parameters) {
                if (hasCommonParameter(parameter)) {
                    Parameter refParameter = new RefParameter(RefType.PARAMETER.getInternalPrefix() + parameter.getName());
                    op.operation.parameter(refParameter);
                } else {
                    parameter = replaceArrayModelForOctetStream(op.operation, parameter);
                    op.operation.parameter(parameter);
                }
            }
        }

        // Process @ApiImplicitParams
        readImplicitParameters(op.method, op.operation);

        processOperationDecorator(op.operation, op.method);

        addImplicitResponses(op.operation);
    }

    @Override
    protected Stream<Parameter> getNonBodyParameters(Type type, List<Annotation> annotations, Set<Type> typesToSkip) {
        return super.getNonBodyParameters(type, annotations, typesToSkip)
                .filter(parameter -> !StringUtils.isBlank(parameter.getName()));
    }

    public static Annotation[][] findParamAnnotations(Method method) {
        Annotation[][] paramAnnotation = method.getParameterAnnotations();

        Method overriddenMethod = ReflectionUtils.getOverriddenMethod(method);
        while (overriddenMethod != null) {
            paramAnnotation = merge(overriddenMethod.getParameterAnnotations(), paramAnnotation);
            overriddenMethod = ReflectionUtils.getOverriddenMethod(overriddenMethod);
        }
        return paramAnnotation;
    }


    private static Annotation[][] merge(Annotation[][] overriddenMethodParamAnnotation,
                                        Annotation[][] currentParamAnnotations) {
        Annotation[][] mergedAnnotations = new Annotation[overriddenMethodParamAnnotation.length][];

        for (int i = 0; i < overriddenMethodParamAnnotation.length; i++) {
            mergedAnnotations[i] = merge(overriddenMethodParamAnnotation[i], currentParamAnnotations[i]);
        }
        return mergedAnnotations;
    }

    private static Annotation[] merge(Annotation[] annotations,
                                      Annotation[] annotations2) {
        List<Annotation> mergedAnnotations = new ArrayList<>();
        mergedAnnotations.addAll(Arrays.asList(annotations));
        mergedAnnotations.addAll(Arrays.asList(annotations2));
        return mergedAnnotations.toArray(new Annotation[0]);
    }

    private Parameter replaceArrayModelForOctetStream(Operation operation, Parameter parameter) {
        if (parameter instanceof BodyParameter
                && operation.getConsumes() != null
                && operation.getConsumes().contains("application/octet-stream")) {
            BodyParameter bodyParam = (BodyParameter) parameter;
            Model schema = bodyParam.getSchema();
            if (schema instanceof ArrayModel) {
                ArrayModel arrayModel = (ArrayModel) schema;
                Property items = arrayModel.getItems();
                if (items != null && items.getFormat() == "byte" && items.getType() == "string") {
                    ModelImpl model = new ModelImpl();
                    model.setFormat("byte");
                    model.setType("string");
                    bodyParam.setSchema(model);
                }
            }
        }
        return parameter;
    }

    public String extractOperationMethod(ApiOperation apiOperation, Method method) {
        if (apiOperation != null && !apiOperation.httpMethod().isEmpty()) {
            return apiOperation.httpMethod().toLowerCase();
        } else if (AnnotationUtils.findAnnotation(method, GET.class) != null) {
            return "get";
        } else if (AnnotationUtils.findAnnotation(method, PUT.class) != null) {
            return "put";
        } else if (AnnotationUtils.findAnnotation(method, POST.class) != null) {
            return "post";
        } else if (AnnotationUtils.findAnnotation(method, DELETE.class) != null) {
            return "delete";
        } else if (AnnotationUtils.findAnnotation(method, OPTIONS.class) != null) {
            return "options";
        } else if (AnnotationUtils.findAnnotation(method, HEAD.class) != null) {
            return "head";
        } else if (AnnotationUtils.findAnnotation(method, io.swagger.jaxrs.PATCH.class) != null) {
            return "patch";
        } else {
            // check for custom HTTP Method annotations
            for (Annotation declaredAnnotation : method.getDeclaredAnnotations()) {
                Annotation[] innerAnnotations = declaredAnnotation.annotationType().getAnnotations();
                for (Annotation innerAnnotation : innerAnnotations) {
                    if (innerAnnotation instanceof HttpMethod) {
                        HttpMethod httpMethod = (HttpMethod) innerAnnotation;
                        return httpMethod.value().toLowerCase();
                    }
                }
            }

            Optional<String> operationMethod = SwaggerExtensionChain.extractOperationMethod(apiOperation, method);
            if (operationMethod.isPresent()) {
                return operationMethod.get();
            }
        }

        return null;
    }

    private Map<String, Model> readAllModels(Type responseClassType) {
        Map<String, Model> modelMap = ModelConverters.getInstance().readAll(responseClassType);
        if (modelMap != null) {
            handleJsonTypeInfo(responseClassType, modelMap);
        }

        return modelMap;
    }

    private void handleJsonTypeInfo(Type responseClassType, Map<String, Model> modelMap) {
        if (responseClassType instanceof ParameterizedType) {
            Type[] actualTypes = ((ParameterizedType) responseClassType).getActualTypeArguments();
            for (Type type : actualTypes) {
                handleJsonTypeInfo(type, modelMap);
            }
        } else if (responseClassType instanceof Class<?>) {
            Class<?> responseClass = ((Class<?>) responseClassType);
            if (responseClass.isArray()) {
                responseClass = responseClass.getComponentType();
            }

            JsonTypeInfo typeInfo = responseClass.getAnnotation(JsonTypeInfo.class);
            if (typeInfo == null || StringUtils.isEmpty(typeInfo.property()) || typeInfo.include().equals(As.EXISTING_PROPERTY)) {
                return;
            }

            Map<String, Property> properties = modelMap.get(responseClass.getSimpleName()).getProperties();
            if (properties != null && !properties.containsKey(typeInfo.property())) {
                properties.put(typeInfo.property(), new StringProperty());
            }
        }
    }
}
