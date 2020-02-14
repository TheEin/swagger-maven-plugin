package com.github.kongchen.swagger.docgen.reader;

import com.github.kongchen.swagger.docgen.ReaderAware;
import com.github.kongchen.swagger.docgen.ResponseMessageOverride;
import com.github.kongchen.swagger.docgen.util.SwaggerExtensionChain;
import com.github.kongchen.swagger.docgen.util.TypeExtracter;
import com.github.kongchen.swagger.docgen.util.TypeWithAnnotations;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import io.swagger.annotations.Authorization;
import io.swagger.annotations.AuthorizationScope;
import io.swagger.annotations.ResponseHeader;
import io.swagger.converter.ModelConverters;
import io.swagger.jaxrs.ext.SwaggerExtensions;
import io.swagger.models.Model;
import io.swagger.models.Operation;
import io.swagger.models.Path;
import io.swagger.models.Response;
import io.swagger.models.Scheme;
import io.swagger.models.SecurityRequirement;
import io.swagger.models.Swagger;
import io.swagger.models.Tag;
import io.swagger.models.parameters.BodyParameter;
import io.swagger.models.parameters.FormParameter;
import io.swagger.models.parameters.HeaderParameter;
import io.swagger.models.parameters.Parameter;
import io.swagger.models.parameters.PathParameter;
import io.swagger.models.parameters.QueryParameter;
import io.swagger.models.properties.ArrayProperty;
import io.swagger.models.properties.MapProperty;
import io.swagger.models.properties.Property;
import io.swagger.models.properties.RefProperty;
import io.swagger.util.ParameterProcessor;
import io.swagger.util.PathUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.reflect.TypeUtils;
import org.apache.commons.lang3.text.StrBuilder;
import org.apache.maven.plugin.logging.Log;
import org.springframework.core.annotation.AnnotationUtils;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * @author chekong on 15/4/28.
 */
public abstract class AbstractReader<R> extends ClassSwaggerReader {

    protected static class ResourceContext<R> {

        public ResourceContext(R resource) {
            this.resource = resource;
        }

        public ResourceContext(OperationContext<R> parent, R resource) {
            this.resource = resource;
            this.parentPath = parent.path;
            this.parentMethod = parent.httpMethod;
            this.readHidden = true;
            this.parentConsumes = parent.consumes;
            this.parentProduces = parent.produces;
            this.parentTags = parent.ctx.tags;
            this.parentParameters = parent.operation.getParameters();
        }

        public R resource;
        public String parentPath = "";
        public String parentMethod;
        public boolean readHidden;
        public String[] parentConsumes = {};
        public String[] parentProduces = {};
        public Map<String, Tag> parentTags = new HashMap<>();
        public List<Parameter> parentParameters = new ArrayList<>();
        public Api api;
        public String path;
        public Map<String, Tag> tags = new HashMap<>();
        public List<SecurityRequirement> securities = new ArrayList<>();
    }

    protected static class OperationContext<R> {

        public OperationContext(ResourceContext<R> ctx) {
            this.ctx = ctx;
            operation.setResponses(new LinkedHashMap<>());
        }

        public final ResourceContext<R> ctx;
        public Method method;
        public ApiOperation api;
        public String httpMethod;
        public String path;
        public String[] consumes = {};
        public String[] produces = {};
        public Operation operation = new Operation();
    }

    public static final String PACKAGE_NAME = "{{packageName}}";
    public static final String CLASS_NAME = "{{className}}";
    public static final String METHOD_NAME = "{{methodName}}";
    public static final String HTTP_METHOD = "{{httpMethod}}";

    /**
     * Supported parameters: {{packageName}}, {{className}}, {{methodName}}, {{httpMethod}}
     * Suggested default value is: "{{className}}_{{methodName}}_{{httpMethod}}"
     */
    public static final String OPERATION_ID_FORMAT_DEFAULT = METHOD_NAME;

    public static final String SUCCESSFUL_OPERATION = "successful operation";

    private static final ResponseContainerConverter RESPONSE_CONTAINER_CONVERTER = new ResponseContainerConverter();

    private Set<Type> typesToSkip = new HashSet<>();

    protected List<ResponseMessageOverride> responseMessageOverrides;

    protected String operationIdFormat;

    public Set<Type> getTypesToSkip() {
        return typesToSkip;
    }

    public void setTypesToSkip(Collection<Type> typesToSkip) {
        this.typesToSkip = new HashSet<>(typesToSkip);
    }

    public void addTypeToSkippedTypes(Type type) {
        this.typesToSkip.add(type);
    }

    public void setResponseMessageOverrides(List<ResponseMessageOverride> responseMessageOverrides) {
        this.responseMessageOverrides = responseMessageOverrides;
    }

    public List<ResponseMessageOverride> getResponseMessageOverrides() {
        return responseMessageOverrides;
    }

    public AbstractReader(Swagger swagger, Log log) {
        super(swagger, log);
        updateExtensionChain();
    }

    /**
     * Method which allows sub-classes to modify the Swagger extension chain.
     */
    protected void updateExtensionChain() {
        SwaggerExtensions.chain().forEachRemaining(extension -> {
            if (extension instanceof ReaderAware) {
                ((ReaderAware) extension).setReader(this);
            }
        });
    }

    protected void getSecurityRequirements(ResourceContext<R> ctx) {
        if (ctx.api == null) {
            return;
        }

        for (Authorization auth : ctx.api.authorizations()) {
            if (auth.value().isEmpty()) {
                continue;
            }
            SecurityRequirement security = new SecurityRequirement();
            security.setName(auth.value());
            for (AuthorizationScope scope : auth.scopes()) {
                if (!scope.scope().isEmpty()) {
                    security.addScope(scope.scope());
                }
            }
            ctx.securities.add(security);
        }
    }

    protected String parseOperationPath(String operationPath, Map<String, String> regexMap) {
        return PathUtils.parsePath(operationPath, regexMap);
    }

    protected void updateOperationParameters(OperationContext<R> op, Map<String, String> regexMap) {
        if (op.ctx.parentParameters != null) {
            for (Parameter param : op.ctx.parentParameters) {
                op.operation.parameter(param);
            }
        }
        for (Parameter param : op.operation.getParameters()) {
            String pattern = regexMap.get(param.getName());
            if (pattern != null) {
                param.setPattern(pattern);
            }
        }
    }

    protected void overrideResponseMessages(Operation operation) {
        if (responseMessageOverrides != null) {
            for (ResponseMessageOverride responseMessage : responseMessageOverrides) {
                operation.response(responseMessage.getCode(), createResponse(responseMessage));
            }
        }
    }

    private Response createResponse(ResponseMessageOverride responseMessage) {
        Response response = new Response()
                .description(responseMessage.getMessage());
        if (responseMessage.getExample() != null) {
            response.example(
                    responseMessage.getExample().getMediaType(),
                    responseMessage.getExample().getValue());
        }
        return response;
    }

    protected Map<String, Property> parseResponseHeaders(ResponseHeader[] headers) {
        if (headers == null) {
            return null;
        }
        Map<String, Property> responseHeaders = null;
        for (ResponseHeader header : headers) {
            if (header.name().isEmpty()) {
                continue;
            }
            if (responseHeaders == null) {
                responseHeaders = new HashMap<>();
            }
            Class<?> cls = header.response();

            if (!cls.equals(Void.class) && !cls.equals(void.class)) {
                Property property = ModelConverters.getInstance().readAsProperty(cls);
                if (property != null) {
                    Property responseProperty;

                    if (header.responseContainer().equalsIgnoreCase("list")) {
                        responseProperty = new ArrayProperty(property);
                    } else if (header.responseContainer().equalsIgnoreCase("map")) {
                        responseProperty = new MapProperty(property);
                    } else {
                        responseProperty = property;
                    }
                    responseProperty.setDescription(header.description());
                    responseHeaders.put(header.name(), responseProperty);
                }
            }
        }
        return responseHeaders;
    }

    protected void updatePath(OperationContext<R> op) {
        if (op.httpMethod == null) {
            return;
        }
        Path path = swagger.getPath(op.path);
        if (path == null) {
            path = new Path();
            swagger.path(op.path, path);
        }
        path.set(op.httpMethod, op.operation);
    }

    protected void updateTagsForOperation(OperationContext<R> op) {
        if (op.api == null) {
            return;
        }
        for (String tag : op.api.tags()) {
            if (!tag.isEmpty()) {
                op.operation.tag(tag);
                swagger.tag(new Tag().name(tag));
            }
        }
    }

    protected boolean canReadApi(ResourceContext<R> ctx) {
        return (ctx.api == null) || (ctx.readHidden) || (!ctx.api.hidden());
    }

    protected Set<Tag> extractTags(Api api) {
        Set<Tag> output = new LinkedHashSet<>();
        if (api == null) {
            return output;
        }

        boolean hasExplicitTags = false;
        for (String tag : api.tags()) {
            if (!tag.isEmpty()) {
                hasExplicitTags = true;
                output.add(new Tag().name(tag));
            }
        }
        if (!hasExplicitTags) {
            // derive tag from api path + description
            String tagString = api.value().replace("/", "");
            if (!tagString.isEmpty()) {
                Tag tag = new Tag().name(tagString);
                if (!api.description().isEmpty()) {
                    tag.description(api.description());
                }
                output.add(tag);
            }
        }
        return output;
    }

    protected void updateOperationProtocols(OperationContext<R> op) {
        if (op.api == null) {
            return;
        }
        String[] protocols = op.api.protocols().split(",");
        for (String protocol : protocols) {
            String trimmed = protocol.trim();
            if (!trimmed.isEmpty()) {
                op.operation.scheme(Scheme.forValue(trimmed));
            }
        }
    }

    protected void updateTagsForApi(ResourceContext<R> ctx) {
        // the value will be used as a tag for 2.0 UNLESS a Tags annotation is present
        for (Tag tag : extractTags(ctx.api)) {
            ctx.tags.put(tag.getName(), tag);
        }
        if (ctx.parentTags != null) {
            ctx.tags.putAll(ctx.parentTags);
        }
        for (Tag tag : ctx.tags.values()) {
            swagger.tag(tag);
        }
    }

    protected boolean isPrimitive(Type cls) {
        return com.github.kongchen.swagger.docgen.util.TypeUtils.isPrimitive(cls);
    }

    protected void updateOperation(OperationContext<R> op) {
        if (op.operation == null) {
            return;
        }
        if (op.operation.getConsumes() == null) {
            for (String mediaType : op.consumes) {
                op.operation.consumes(mediaType);
            }
        }
        if (op.operation.getProduces() == null) {
            for (String mediaType : op.produces) {
                op.operation.produces(mediaType);
            }
        }

        if (op.operation.getTags() == null) {
            for (String tagString : op.ctx.tags.keySet()) {
                op.operation.tag(tagString);
            }
        }
        for (SecurityRequirement security : op.ctx.securities) {
            op.operation.security(security);
        }
    }

    private boolean isApiParamHidden(List<Annotation> parameterAnnotations) {
        for (Annotation parameterAnnotation : parameterAnnotations) {
            if (parameterAnnotation instanceof ApiParam) {
                return ((ApiParam) parameterAnnotation).hidden();
            }
        }

        return false;
    }

    // this is final to enforce that only the implementation method below can be overridden, to avoid confusion
    protected final List<Parameter> getParameters(Type type, List<Annotation> annotations) {
        return getParameters(type, annotations, typesToSkip);
    }

    // this method exists so that outside callers can choose their own custom types to skip
    protected List<Parameter> getParameters(Type type, List<Annotation> annotations, Set<Type> typesToSkip) {
        if (isApiParamHidden(annotations)) {
            return Collections.emptyList();
        }

        Class<?> cls = TypeUtils.getRawType(type, type);
        log.debug("Looking for path/query/header/form/cookie params in " + cls);

        return SwaggerExtensionChain
                .extractParameters(annotations, type, typesToSkip)
                .map(parameters -> parameters.stream()
                        .map(parameter -> ParameterProcessor.applyAnnotations(swagger, parameter, type, annotations))
                        .filter(Objects::nonNull)
                        .filter(parameter -> !StringUtils.isBlank(parameter.getName()))
                        .collect(Collectors.toList()))
                .filter(((Predicate<List<Parameter>>) List::isEmpty).negate())
                .orElseGet(() -> {
                    if (typesToSkip.isEmpty()) {
                        log.debug("Looking for body params in " + cls);
                        return Optional.ofNullable(
                                ParameterProcessor.applyAnnotations(swagger, null, type, annotations))
                                .map(Collections::singletonList)
                                .orElseGet(Collections::emptyList);
                    }
                    return Collections.emptyList();
                });
    }

    protected void updateApiResponse(Operation operation, ApiResponses responseAnnotation) {
        boolean contains200 = false;
        boolean contains2xx = false;
        for (ApiResponse apiResponse : responseAnnotation.value()) {
            Map<String, Property> responseHeaders = parseResponseHeaders(apiResponse.responseHeaders());
            Class<?> responseClass = apiResponse.response();
            Response response = new Response()
                    .description(apiResponse.message())
                    .headers(responseHeaders);

            if (responseClass.equals(Void.class)) {
                if (operation.getResponses() != null) {
                    Response apiOperationResponse = operation.getResponses().get(String.valueOf(apiResponse.code()));
                    if (apiOperationResponse != null) {
                        response.setSchema(apiOperationResponse.getSchema());
                    }
                }
            } else if (isPrimitive(responseClass)) {
                Property property = ModelConverters.getInstance().readAsProperty(responseClass);
                if (property != null) {
                    response.setSchema(RESPONSE_CONTAINER_CONVERTER.withResponseContainer(apiResponse.responseContainer(), property));
                }
            } else {
                Map<String, Model> models = ModelConverters.getInstance().read(responseClass);
                for (String key : models.keySet()) {
                    final Property schema = new RefProperty().asDefault(key);
                    response.setSchema(RESPONSE_CONTAINER_CONVERTER.withResponseContainer(apiResponse.responseContainer(), schema));
                    swagger.model(key, models.get(key));
                }
                models = ModelConverters.getInstance().readAll(responseClass);
                for (Map.Entry<String, Model> entry : models.entrySet()) {
                    swagger.model(entry.getKey(), entry.getValue());
                }

                if (response.getSchema() == null) {
                    Map<String, Response> responses = operation.getResponses();
                    if (responses != null) {
                        Response apiOperationResponse = responses.get(String.valueOf(apiResponse.code()));
                        if (apiOperationResponse != null) {
                            response.setSchema(apiOperationResponse.getSchema());
                        }
                    }
                }
            }

            if (apiResponse.code() == 0) {
                operation.defaultResponse(response);
            } else {
                operation.response(apiResponse.code(), response);
            }
            if (apiResponse.code() == 200) {
                contains200 = true;
            } else if (apiResponse.code() > 200 && apiResponse.code() < 300) {
                contains2xx = true;
            }
        }
        if (!contains200 && contains2xx) {
            Map<String, Response> responses = operation.getResponses();
            //technically should not be possible at this point, added to be safe
            if (responses != null) {
                responses.remove("200");
            }
        }
    }

    protected void updateOperationProduces(OperationContext<R> op) {
        if (op.ctx.parentProduces != null) {
            Set<String> both = new LinkedHashSet<>(Arrays.asList(op.produces));
            both.addAll(Arrays.asList(op.ctx.parentProduces));
            if (op.operation.getProduces() != null) {
                both.addAll(op.operation.getProduces());
            }
            op.produces = both.toArray(new String[0]);
        }
    }

    protected void updateOperationConsumes(OperationContext<R> op) {
        if (op.ctx.parentConsumes != null) {
            Set<String> both = new LinkedHashSet<>(Arrays.asList(op.consumes));
            both.addAll(Arrays.asList(op.ctx.parentConsumes));
            if (op.operation.getConsumes() != null) {
                both.addAll(op.operation.getConsumes());
            }
            op.consumes = both.toArray(new String[0]);
        }
    }

    protected void readImplicitParameters(Method method, Operation operation) {
        ApiImplicitParams implicitParams = AnnotationUtils.findAnnotation(method, ApiImplicitParams.class);
        if (implicitParams == null) {
            return;
        }
        for (ApiImplicitParam param : implicitParams.value()) {
            Class<?> cls;
            try {
                cls = param.dataTypeClass() == Void.class ?
                        Class.forName(param.dataType()) :
                        param.dataTypeClass();
            } catch (ClassNotFoundException e) {
                cls = method.getDeclaringClass();
            }

            Parameter p = readImplicitParam(param, cls);
            if (p != null) {
                if (p instanceof BodyParameter) {
                    Iterator<Parameter> iterator = operation.getParameters().iterator();
                    while (iterator.hasNext()) {
                        Parameter parameter = iterator.next();
                        if (parameter instanceof BodyParameter) {
                            iterator.remove();
                        }
                    }
                }
                operation.addParameter(p);
            }
        }
    }

    protected Parameter readImplicitParam(ApiImplicitParam param, Class<?> apiClass) {
        Parameter parameter;
        if (param.paramType().equalsIgnoreCase("path")) {
            parameter = new PathParameter();
        } else if (param.paramType().equalsIgnoreCase("query")) {
            parameter = new QueryParameter();
        } else if (param.paramType().equalsIgnoreCase("form") || param.paramType().equalsIgnoreCase("formData")) {
            parameter = new FormParameter();
        } else if (param.paramType().equalsIgnoreCase("body")) {
            parameter = new BodyParameter();
        } else if (param.paramType().equalsIgnoreCase("header")) {
            parameter = new HeaderParameter();
        } else {
            return null;
        }

        return ParameterProcessor.applyAnnotations(swagger, parameter, apiClass, Collections.singletonList(param));
    }

    void processOperationDecorator(Operation operation, Method method) {
        SwaggerExtensionChain.decorateOperation(operation, method);
    }

    protected void addImplicitResponses(Operation operation) {
        boolean addDefaultResponse = Optional
                .ofNullable(operation.getResponses())
                .map(Map::isEmpty)
                .orElse(true);
        if (addDefaultResponse) {
            operation.defaultResponse(new Response().description(SUCCESSFUL_OPERATION));
        }
    }

    protected String getOperationId(Method method, String httpMethod) {
        if (this.operationIdFormat == null) {
            this.operationIdFormat = OPERATION_ID_FORMAT_DEFAULT;
        }

        String packageName = method.getDeclaringClass().getPackage().getName();
        String className = method.getDeclaringClass().getSimpleName();
        String methodName = method.getName();

        StrBuilder sb = new StrBuilder(this.operationIdFormat);
        sb.replaceAll(PACKAGE_NAME, packageName);
        sb.replaceAll(CLASS_NAME, className);
        sb.replaceAll(METHOD_NAME, methodName);
        sb.replaceAll(HTTP_METHOD, httpMethod);

        return sb.toString();
    }

    public List<Parameter> extractTypes(Class<?> cls, Set<Type> typesToSkip, List<Annotation> additionalAnnotations) {
        TypeExtracter extractor = new TypeExtracter();
        Collection<TypeWithAnnotations> typesWithAnnotations = extractor.extractTypes(cls);

        List<Parameter> output = new ArrayList<>();
        for (TypeWithAnnotations typeWithAnnotations : typesWithAnnotations) {

            Type type = typeWithAnnotations.getType();
            List<Annotation> annotations = new ArrayList<>(additionalAnnotations);
            annotations.addAll(typeWithAnnotations.getAnnotations());

            /*
             * Skip the type of the bean itself when recursing into its members
             * in order to avoid a cycle (stack overflow), as crazy as that user
             * code would have to be.
             *
             * There are no tests to prove this works because the test bean
             * classes are shared with SwaggerReaderTest and Swagger's own logic
             * doesn't prevent this problem.
             */
            Set<Type> recurseTypesToSkip = new HashSet<>(typesToSkip);
            recurseTypesToSkip.add(cls);

            output.addAll(this.getParameters(type, annotations, recurseTypesToSkip));
        }

        return output;
    }

    public String getOperationIdFormat() {
        return operationIdFormat;
    }

    public void setOperationIdFormat(String operationIdFormat) {
        this.operationIdFormat = operationIdFormat;
    }
}

