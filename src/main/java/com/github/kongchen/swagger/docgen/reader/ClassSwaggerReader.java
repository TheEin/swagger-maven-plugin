package com.github.kongchen.swagger.docgen.reader;

import com.github.kongchen.swagger.docgen.GenerateException;
import io.swagger.models.Swagger;
import org.apache.maven.plugin.logging.Log;

import java.util.Set;

/**
 * @author chekong on 15/4/28.
 */
public abstract class ClassSwaggerReader {

    protected final Swagger swagger;

    protected final Log log;

    public ClassSwaggerReader(Swagger swagger, Log log) {
        this.swagger = swagger;
        this.log = log;
    }

    public Swagger getSwagger() {
        return swagger;
    }

    public abstract void read(Set<Class<?>> classes) throws GenerateException;
}
