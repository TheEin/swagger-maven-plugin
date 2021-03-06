package com.github.kongchen.swagger.docgen.jaxrs;

import com.github.kongchen.swagger.docgen.ReaderAware;
import com.github.kongchen.swagger.docgen.reader.AbstractReader;
import com.github.kongchen.swagger.docgen.reader.JaxrsReader;
import com.sun.jersey.api.core.InjectParam;
import com.sun.jersey.core.header.FormDataContentDisposition;
import io.swagger.jaxrs.ext.AbstractSwaggerExtension;
import io.swagger.jaxrs.ext.SwaggerExtension;
import io.swagger.models.parameters.Parameter;
import org.apache.commons.lang3.reflect.TypeUtils;
import org.springframework.cloud.openfeign.SpringQueryMap;

import javax.ws.rs.BeanParam;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * This extension extracts the parameters inside a {@code @BeanParam} by
 * expanding the target bean type's fields/methods/constructor parameters and
 * recursively feeding them back through the {@link JaxrsReader}.
 *
 * @author chekong on 15/5/9.
 */
public class ContainerParamExtension extends AbstractSwaggerExtension implements ReaderAware {

    public static final Class<?>[] CONTAINER_PARAM_ANNOTATIONS = {BeanParam.class, InjectParam.class, SpringQueryMap.class};

    private AbstractReader reader;

    @Override
    public void setReader(AbstractReader reader) {
        this.reader = reader;
    }

    @Override
    public List<Parameter> extractParameters(List<Annotation> annotations, Type type, Set<Type> typesToSkip, Iterator<SwaggerExtension> chain) {
        Class<?> cls;
        try {
            cls = TypeUtils.getRawType(type, type);
        } catch (IllegalArgumentException e) {
            return super.extractParameters(annotations, type, typesToSkip, chain);
        }
        if (shouldIgnoreClass(cls) || typesToSkip.contains(type)) {
            // stop the processing chain
            typesToSkip.add(type);
            return super.extractParameters(annotations, type, typesToSkip, chain);
        }
        for (Annotation annotation : annotations) {
            for (Class<?> validParameterAnnotation : CONTAINER_PARAM_ANNOTATIONS) {
                if (validParameterAnnotation.isAssignableFrom(annotation.annotationType())) {
                    return reader.extractTypes(cls, typesToSkip, new ArrayList<>());
                }
            }
        }
        return super.extractParameters(annotations, type, typesToSkip, chain);
    }

    @Override
    public boolean shouldIgnoreClass(Class<?> cls) {
        return FormDataContentDisposition.class.equals(cls);
    }
}
