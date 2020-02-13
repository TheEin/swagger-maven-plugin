package com.github.kongchen.swagger.docgen.reader;

import com.github.kongchen.swagger.docgen.GenerateException;
import io.swagger.models.Swagger;
import io.swagger.servlet.Reader;
import org.apache.maven.plugin.logging.Log;

import java.util.Set;

/**
 * A dedicated {@link ClassSwaggerReader} to scan Serlet classes.
 */
public class ServletReader extends AbstractReader {

    public ServletReader(Swagger swagger, Log log) {
        super(swagger, log);
    }

    @Override
    public void read(Set<Class<?>> classes) throws GenerateException {
        Reader.read(swagger, classes);
    }

}
