package com.github.kongchen.swagger.docgen.reader;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.type.ResolvedType;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.kongchen.swagger.docgen.GenerateException;
import com.github.kongchen.swagger.docgen.nexign.NexignVendorExtensions;
import io.swagger.annotations.ApiModelProperty;
import io.swagger.converter.ModelConverter;
import io.swagger.converter.ModelConverterContext;
import io.swagger.jackson.ModelResolver;
import io.swagger.models.Model;
import io.swagger.models.ModelImpl;
import io.swagger.models.properties.Property;
import io.swagger.models.properties.StringProperty;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotationUtils;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @author chekong on 15/5/19.
 */
public class ModelModifier extends ModelResolver {
    private Map<JavaType, JavaType> modelSubtitutes = new HashMap<JavaType, JavaType>();
    private List<String> apiModelPropertyAccessExclusions = new ArrayList<String>();

    private static Logger LOGGER = LoggerFactory.getLogger(ModelModifier.class);

    public ModelModifier(ObjectMapper mapper) {
        super(mapper);
    }

    public void addModelSubstitute(String fromClass, String toClass) throws GenerateException {
        JavaType type = null;
        JavaType toType = null;
        try {
            type = _mapper.constructType(Class.forName(fromClass));
        } catch (ClassNotFoundException e) {
            LOGGER.warn(String.format("Problem with loading class: %s. Mapping from: %s to: %s will be ignored.",
                    fromClass, fromClass, toClass));
        }
        try {
            toType = _mapper.constructType(Class.forName(toClass));
        } catch (ClassNotFoundException e) {
            LOGGER.warn(String.format("Problem with loading class: %s. Mapping from: %s to: %s will be ignored.",
                    toClass, fromClass, toClass));
        }
        if (type != null && toType != null) {
            modelSubtitutes.put(type, toType);
        }
    }

    public List<String> getApiModelPropertyAccessExclusions() {
        return apiModelPropertyAccessExclusions;
    }

    public void setApiModelPropertyAccessExclusions(List<String> apiModelPropertyAccessExclusions) {
        this.apiModelPropertyAccessExclusions = apiModelPropertyAccessExclusions;
    }

    @Override
    public Property resolveProperty(Type type, ModelConverterContext context, Annotation[] annotations, Iterator<ModelConverter> chain) {
        // for method parameter types we get here Type but we need JavaType
        JavaType javaType = toJavaType(type);
        if (modelSubtitutes.containsKey(javaType)) {
            return super.resolveProperty(modelSubtitutes.get(javaType), context, annotations, chain);
        } else if (chain.hasNext()) {
            return chain.next().resolveProperty(type, context, annotations, chain);
        } else {
            return super.resolveProperty(type, context, annotations, chain);
        }

    }

    @Override
    public Model resolve(Type type, ModelConverterContext context, Iterator<ModelConverter> chain) {
        // for method parameter types we get here Type but we need JavaType
        JavaType javaType = toJavaType(type);
        if (modelSubtitutes.containsKey(javaType)) {
            return super.resolve(modelSubtitutes.get(javaType), context, chain);
        } else {
            return super.resolve(type, context, chain);
        }
    }

    @Override
    public Model resolve(JavaType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
        Model model = super.resolve(type, context, chain);

        handleJsonTypeInfo(type, model);

        // If there are no @ApiModelPropety exclusions configured, return the untouched model
        if (apiModelPropertyAccessExclusions == null || apiModelPropertyAccessExclusions.isEmpty()) {
            return model;
        }

        Class<?> cls = type.getRawClass();

        for (Method method : cls.getDeclaredMethods()) {
            ApiModelProperty apiModelPropertyAnnotation = AnnotationUtils.findAnnotation(method, ApiModelProperty.class);

            processProperty(apiModelPropertyAnnotation, model);
        }

        for (Field field : FieldUtils.getAllFields(cls)) {
            ApiModelProperty apiModelPropertyAnnotation = AnnotationUtils.getAnnotation(field, ApiModelProperty.class);

            processProperty(apiModelPropertyAnnotation, model);
        }

        return model;
    }


    private void handleJsonTypeInfo(Type type, Model model) {
        if (type instanceof ResolvedType) {
            Class<?> clazz = ((ResolvedType) type).getRawClass();
            if (clazz.isArray()) {
                clazz = clazz.getComponentType();
            }

            JsonTypeInfo typeInfo = clazz.getAnnotation(JsonTypeInfo.class);
            if (typeInfo != null) {
                if (model == null) {
                    throw new IllegalStateException("Undefined model for response class " + clazz.getSimpleName());
                }
                handleJsonTypeInfo(typeInfo, clazz, model);
            }
        }
    }

    private void handleJsonTypeInfo(JsonTypeInfo typeInfo, Class<?> clazz, Model model) {
        String propertyName = typeInfo.property();
        if (!StringUtils.isEmpty(propertyName)) {
            if (!typeInfo.include().equals(JsonTypeInfo.As.EXISTING_PROPERTY)) {
                addProperty(model, propertyName, new StringProperty());
            }
            if (model instanceof ModelImpl) {
                ModelImpl m = (ModelImpl) model;
                m.setDiscriminator(propertyName);
                m.addRequired(propertyName);
            }
        }

        JsonSubTypes subTypes = clazz.getAnnotation(JsonSubTypes.class);
        if (subTypes != null) {
            handleJsonSubTypes(subTypes, clazz, model);
        }
    }

    private void handleJsonSubTypes(JsonSubTypes subTypes, Class<?> clazz, Model model) {
        Map<String, String> discriminatorMapping = new LinkedHashMap<>();
        for (JsonSubTypes.Type subType : subTypes.value()) {
            discriminatorMapping.put(subType.name(), subType.value().getSimpleName());
        }
        model.getVendorExtensions().put(NexignVendorExtensions.DISCRIMINATOR_MAPPING, discriminatorMapping);
    }

    private void addProperty(Model model, String name, Property property) {
        Map<String, Property> properties = model.getProperties();
        if (properties == null) {
            if (model instanceof ModelImpl) {
                ModelImpl m = (ModelImpl) model;
                m.addProperty(name, property);
            }
        } else if (!properties.containsKey(name)) {
            properties.put(name, property);
        }
    }

    /**
     * Remove property from {@link Model} for provided {@link ApiModelProperty}.
     *
     * @param apiModelPropertyAnnotation annotation
     * @param model                      model with properties
     */
    private void processProperty(ApiModelProperty apiModelPropertyAnnotation, Model model) {
        if (apiModelPropertyAnnotation == null) {
            return;
        }

        String apiModelPropertyAccess = apiModelPropertyAnnotation.access();
        String apiModelPropertyName = apiModelPropertyAnnotation.name();

        // If the @ApiModelProperty is not populated with both #name and #access, skip it
        if (apiModelPropertyAccess.isEmpty() || apiModelPropertyName.isEmpty()) {
            return;
        }

        // Check to see if the value of @ApiModelProperty#access is one to exclude.
        // If so, remove it from the previously-calculated model.
        if (apiModelPropertyAccessExclusions.contains(apiModelPropertyAccess)) {
            model.getProperties().remove(apiModelPropertyName);
        }
    }

    /**
     * Converts {@link Type} to {@link JavaType}.
     *
     * @param type object to convert
     * @return object converted to {@link JavaType}
     */
    private JavaType toJavaType(Type type) {
        JavaType typeToFind;
        if (type instanceof JavaType) {
            typeToFind = (JavaType) type;
        } else {
            typeToFind = _mapper.constructType(type);
        }
        return typeToFind;
    }
}
