package com.wordnik.jaxrs;

import io.swagger.models.Swagger;
import org.apache.maven.plugin.logging.Log;

import java.util.Set;

/**
 * @author Igor Gursky
 * 11.12.2015.
 */
public class CustomJaxrsReader extends VendorExtensionsJaxrsReader {

    public CustomJaxrsReader(Swagger swagger, Log log) {
        super(swagger, log);
    }

    @Override
    public void read(Set<Class<?>> classes) {
        super.read(classes);
        swagger.getInfo().setDescription("Processed with CustomJaxrsReader");
    }
}
