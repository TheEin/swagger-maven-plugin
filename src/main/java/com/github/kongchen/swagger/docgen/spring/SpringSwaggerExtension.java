package com.github.kongchen.swagger.docgen.spring;

import com.fasterxml.jackson.databind.JavaType;
import com.github.kongchen.swagger.docgen.util.ArrayUtils;
import com.google.common.base.Strings;
import com.google.common.collect.ClassToInstanceMap;
import com.google.common.collect.Lists;
import com.google.common.collect.MutableClassToInstanceMap;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.converter.ModelConverters;
import io.swagger.jaxrs.ext.AbstractSwaggerExtension;
import io.swagger.jaxrs.ext.SwaggerExtension;
import io.swagger.models.Swagger;
import io.swagger.models.parameters.CookieParameter;
import io.swagger.models.parameters.FormParameter;
import io.swagger.models.parameters.HeaderParameter;
import io.swagger.models.parameters.Parameter;
import io.swagger.models.parameters.PathParameter;
import io.swagger.models.parameters.QueryParameter;
import io.swagger.models.properties.ArrayProperty;
import io.swagger.models.properties.FileProperty;
import io.swagger.models.properties.Property;
import io.swagger.models.properties.StringProperty;
import io.swagger.util.ParameterProcessor;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.reflect.MethodUtils;
import org.apache.commons.lang3.reflect.TypeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

import java.beans.PropertyDescriptor;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * @author chekong on 15/4/27.
 */
public class SpringSwaggerExtension extends AbstractSwaggerExtension {

    private static final Logger log = LoggerFactory.getLogger(SpringSwaggerExtension.class);

    private static final String DEFAULT_VALUE = "\n\t\t\n\t\t\n\ue000\ue001\ue002\n\t\t\t\t\n";

    private static final RequestParam DEFAULT_REQUEST_PARAM = (RequestParam) MethodUtils.getMatchingMethod(AnnotationBearer.class, "get", String.class).getParameterAnnotations()[0][0];

    // Class specificly for holding default value annotations
    private static class AnnotationBearer {
        /**
         * Only used to get annotations..
         *
         * @param requestParam ignore this
         */
        public void get(@RequestParam(required = false, defaultValue = "") String requestParam) {
        }
    }

    @Override
    public String extractOperationMethod(ApiOperation apiOperation, Method method, Iterator<SwaggerExtension> chain) {
        return Optional.ofNullable(AnnotationUtils.findAnnotation(method, RequestMapping.class))
                .map(RequestMapping::method)
                .map(ArrayUtils::getSingleElement)
                .map(Enum::name)
                .map(String::toLowerCase)
                .orElseGet(() -> super.extractOperationMethod(apiOperation, method, chain));
    }

    @Override
    public List<Parameter> extractParameters(List<Annotation> annotations, Type type, Set<Type> typesToSkip, Iterator<SwaggerExtension> chain) {
        if (this.shouldIgnoreType(type, typesToSkip)) {
            return Collections.emptyList();
        }
        ClassToInstanceMap<Annotation> annotationMap = toMap(annotations);

        List<Parameter> parameters = new ArrayList<>();
        parameters.addAll(extractParametersFromModelAttributeAnnotation(type, annotationMap));
        parameters.addAll(extractParametersFromAnnotation(type, annotationMap));

        if (!parameters.isEmpty()) {
            return parameters;
        }
        return super.extractParameters(annotations, type, typesToSkip, chain);
    }

    private ClassToInstanceMap<Annotation> toMap(Collection<? extends Annotation> annotations) {
        ClassToInstanceMap<Annotation> annotationMap = MutableClassToInstanceMap.create();
        for (Annotation annotation : annotations) {
            if (annotation == null) {
                continue;
            }
            annotationMap.put(annotation.annotationType(), annotation);
        }
        return annotationMap;
    }

    private boolean noneBindAnnotations(ClassToInstanceMap<Annotation> annotations) {
        for (Class<?> types : annotations.keySet()) {
            if (types.getName().startsWith("org.springframework.web.bind.annotation")) {
                return false;
            }
        }
        return true;
    }

    private List<Parameter> extractParametersFromAnnotation(Type type, ClassToInstanceMap<Annotation> annotations) {
        List<Parameter> parameters;

        ApiParam apiParam = annotations.getInstance(ApiParam.class);
        Annotation paramAnnotation;
        boolean paramRequired;

        if (isRequestParamType(type, annotations)) {
            RequestParam requestParam = Optional
                    .ofNullable(annotations.getInstance(RequestParam.class))
                    .orElse(DEFAULT_REQUEST_PARAM);
            paramAnnotation = requestParam;
            paramRequired = requestParam.required();
            parameters = Collections.singletonList(extractRequestParam(type, requestParam));
        } else if (annotations.containsKey(PathVariable.class)) {
            PathVariable pathVariable = annotations.getInstance(PathVariable.class);
            paramAnnotation = pathVariable;
            paramRequired = pathVariable.required();
            PathParameter pathParameter = extractPathVariable(type, pathVariable);
            parameters = Collections.singletonList(pathParameter);
        } else if (annotations.containsKey(RequestHeader.class)) {
            RequestHeader requestHeader = annotations.getInstance(RequestHeader.class);
            paramAnnotation = requestHeader;
            paramRequired = requestHeader.required();
            HeaderParameter headerParameter = extractRequestHeader(type, requestHeader);
            parameters = Collections.singletonList(headerParameter);
        } else if (annotations.containsKey(CookieValue.class)) {
            CookieValue cookieValue = annotations.getInstance(CookieValue.class);
            paramAnnotation = cookieValue;
            paramRequired = cookieValue.required();
            CookieParameter cookieParameter = extractCookieValue(type, cookieValue);
            parameters = Collections.singletonList(cookieParameter);
        } else if (annotations.containsKey(RequestPart.class)) {
            RequestPart requestPart = annotations.getInstance(RequestPart.class);
            paramAnnotation = requestPart;
            paramRequired = requestPart.required();
            FormParameter formParameter = extractRequestPart(type, requestPart);
            parameters = Collections.singletonList(formParameter);
        } else if (annotations.containsKey(RequestBody.class)) {
            RequestBody requestBody = annotations.getInstance(RequestBody.class);
            paramAnnotation = requestBody;
            paramRequired = requestBody.required();
            parameters = Collections.emptyList();
        } else {
            return Collections.emptyList();
        }

        if (apiParam != null && apiParam.required() != paramRequired) {
            throw new IllegalStateException("Conflicting 'required' values on annotations: " +
                    apiParam.annotationType() + "(required = " + apiParam.required() + ") but " +
                    paramAnnotation.annotationType() + "(required = " + paramRequired + ")");
        }
        return parameters;
    }

    private Parameter extractRequestParam(Type type, RequestParam requestParam) {
        String paramName = StringUtils.defaultIfEmpty(requestParam.value(), requestParam.name());
        QueryParameter queryParameter = new QueryParameter().name(paramName)
                .required(requestParam.required());

        if (!DEFAULT_VALUE.equals(requestParam.defaultValue())) {
            queryParameter.setDefaultValue(requestParam.defaultValue());
            // Supplying a default value implicitly sets required() to false.
            queryParameter.setRequired(false);
        }
        Property schema = readAsPropertyIfPrimitive(type);
        if (schema != null) {
            queryParameter.setProperty(schema);
        }

        return queryParameter;
    }

    private FormParameter extractRequestPart(Type type, RequestPart requestPart) {
        String paramName = StringUtils.defaultIfEmpty(requestPart.value(), requestPart.name());
        FormParameter formParameter = new FormParameter().name(paramName)
                .required(requestPart.required());

        JavaType ct = constructType(type);
        Property schema;

        if (MultipartFile.class.isAssignableFrom(ct.getRawClass())) {
            schema = new FileProperty();
        } else if (ct.isContainerType() &&
                MultipartFile.class.isAssignableFrom(ct.getContentType().getRawClass())) {
            schema = new ArrayProperty().items(new FileProperty());
        } else {
            schema = ModelConverters.getInstance().readAsProperty(type);
        }

        if (schema != null) {
            formParameter.setProperty(schema);
        }
        return formParameter;
    }

    private CookieParameter extractCookieValue(Type type, CookieValue cookieValue) {
        String paramName = StringUtils.defaultIfEmpty(cookieValue.value(), cookieValue.name());
        CookieParameter cookieParameter = new CookieParameter().name(paramName)
                .required(cookieValue.required());
        Property schema = readAsPropertyIfPrimitive(type);
        if (!DEFAULT_VALUE.equals(cookieValue.defaultValue())) {
            cookieParameter.setDefaultValue(cookieValue.defaultValue());
            cookieParameter.setRequired(false);
        }
        if (schema != null) {
            cookieParameter.setProperty(schema);
        }
        return cookieParameter;
    }

    private HeaderParameter extractRequestHeader(Type type, RequestHeader requestHeader) {
        String paramName = StringUtils.defaultIfEmpty(requestHeader.value(), requestHeader.name());
        HeaderParameter headerParameter = new HeaderParameter().name(paramName)
                .required(requestHeader.required());
        Property schema = readAsPropertyIfPrimitive(type);
        if (!DEFAULT_VALUE.equals(requestHeader.defaultValue())) {
            headerParameter.setDefaultValue(requestHeader.defaultValue());
            headerParameter.setRequired(false);
        }
        if (schema != null) {
            headerParameter.setProperty(schema);
        }
        return headerParameter;
    }

    private PathParameter extractPathVariable(Type type, PathVariable pathVariable) {
        String paramName = StringUtils.defaultIfEmpty(pathVariable.value(), pathVariable.name());
        PathParameter pathParameter = new PathParameter().name(paramName);
        Property schema = readAsPropertyIfPrimitive(type);
        if (schema != null) {
            pathParameter.setProperty(schema);
        }
        return pathParameter;
    }

    private Property readAsPropertyIfPrimitive(Type type) {
        if (com.github.kongchen.swagger.docgen.util.TypeUtils.isPrimitive(type)) {
            return ModelConverters.getInstance().readAsProperty(type);
        } else {
            String msg = String.format("Non-primitive type: %s used as request/path/cookie parameter", type);
            log.warn(msg);

            // fallback to string if type is simple wrapper for String to support legacy code
            JavaType ct = constructType(type);
            if (isSimpleWrapperForString(ct.getRawClass())) {
                log.warn(String.format("Non-primitive type: %s used as string for request/path/cookie parameter", type));
                return new StringProperty();
            }
        }
        return null;
    }

    private boolean isSimpleWrapperForString(Class<?> clazz) {
        try {
            Constructor<?>[] constructors = clazz.getConstructors();
            if (constructors.length <= 2) {
                if (constructors.length == 1) {
                    return clazz.getConstructor(String.class) != null;
                } else if (constructors.length == 2) {
                    return clazz.getConstructor(String.class) != null && clazz.getConstructor() != null;
                }
            }
            return false;
        } catch (NoSuchMethodException e) {
            // ignore
            return false;
        }
    }

    private List<Parameter> extractParametersFromModelAttributeAnnotation(Type type, ClassToInstanceMap<Annotation> annotations) {
        ModelAttribute modelAttribute = annotations.getInstance(ModelAttribute.class);
        if ((modelAttribute == null || noneBindAnnotations(annotations))
                && BeanUtils.isSimpleProperty(TypeUtils.getRawType(type, null))) {
            return Collections.emptyList();
        }

        List<Parameter> parameters = new ArrayList<>();
        Class<?> clazz = TypeUtils.getRawType(type, type);
        for (PropertyDescriptor propertyDescriptor : BeanUtils.getPropertyDescriptors(clazz)) {
            // Get all the valid setter methods inside the bean
            Method propertyDescriptorSetter = propertyDescriptor.getWriteMethod();
            if (propertyDescriptorSetter != null) {
                ApiParam propertySetterApiParam = AnnotationUtils.findAnnotation(propertyDescriptorSetter, ApiParam.class);
                if (propertySetterApiParam == null) {
                    // If we find a setter that doesn't have @ApiParam annotation, then skip it
                    continue;
                }

                // Here we have a bean setter method that is annotated with @ApiParam, but we still
                // need to know what type of parameter to create. In order to do this, we look for
                // any annotation attached to the first method parameter of the setter function.
                Annotation[][] parameterAnnotations = propertyDescriptorSetter.getParameterAnnotations();
                if (parameterAnnotations == null || parameterAnnotations.length == 0) {
                    continue;
                }

                Class<?> parameterClass = propertyDescriptor.getPropertyType();
                List<Parameter> propertySetterExtractedParameters = this.extractParametersFromAnnotation(
                        parameterClass, toMap(Arrays.asList(parameterAnnotations[0])));

                for (Parameter parameter : propertySetterExtractedParameters) {
                    if (Strings.isNullOrEmpty(parameter.getName())) {
                        parameter.setName(propertyDescriptor.getDisplayName());
                    }
                    ParameterProcessor.applyAnnotations(new Swagger(), parameter, type, Lists.newArrayList(propertySetterApiParam));
                }
                parameters.addAll(propertySetterExtractedParameters);
            }
        }

        return parameters;
    }

    private boolean isRequestParamType(Type type, ClassToInstanceMap<Annotation> annotations) {
        RequestParam requestParam = annotations.getInstance(RequestParam.class);
        return requestParam != null || (BeanUtils.isSimpleProperty(TypeUtils.getRawType(type, type)) && noneBindAnnotations(annotations));
    }

    @Override
    public boolean shouldIgnoreType(Type type, Set<Type> typesToSkip) {
        Class<?> clazz = TypeUtils.getRawType(type, type);
        if (clazz == null) {
            return false;
        }

        String clazzName = clazz.getName();

        switch (clazzName) {
            case "javax.servlet.ServletRequest":
            case "javax.servlet.ServletResponse":
            case "javax.servlet.http.HttpSession":
            case "javax.servlet.http.PushBuilder":
            case "java.security.Principal":
            case "java.io.OutputStream":
            case "java.io.Writer":
                return true;
            default:
        }

        return clazzName.startsWith("org.springframework") &&
                !"org.springframework.web.multipart.MultipartFile".equals(clazzName);
    }
}
