package com.github.kongchen.swagger.docgen.reader;

import com.github.kongchen.swagger.docgen.GenerateException;
import io.swagger.jaxrs.Reader;
import io.swagger.models.Swagger;
import org.apache.maven.plugin.logging.Log;

import java.util.Set;

/**
 * This API reader is directly using the swagger internal {@link Reader} to scan the classes.
 * This reader is used when the exact output as the runtime generated swagger file is necessary.
 */
public class SwaggerReader extends AbstractReader<Class<?>> {

    public SwaggerReader(Swagger swagger, Log log) {
        super(swagger, log);
    }

    @Override
    public void read(Set<Class<?>> classes) throws GenerateException {
        new Reader(swagger).read(classes);
    }

}
