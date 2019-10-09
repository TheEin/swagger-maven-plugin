package com.github.kongchen.swagger.docgen.nginx;

import com.github.kongchen.swagger.docgen.mavenplugin.NginxConfig;
import com.github.kongchen.swagger.docgen.reader.JaxrsReader;
import io.swagger.jaxrs.ext.SwaggerExtension;
import io.swagger.jaxrs.ext.SwaggerExtensions;
import io.swagger.models.Swagger;
import org.apache.maven.plugin.logging.Log;

import java.util.ArrayList;

public class NginxJaxrsReader extends JaxrsReader {

    public NginxJaxrsReader(Swagger swagger, NginxConfig nginxConfig, Log LOG) {
        super(swagger, LOG);

        ArrayList<SwaggerExtension> extensions = new ArrayList<>(
                SwaggerExtensions.getExtensions().size() + 1);
        extensions.addAll(SwaggerExtensions.getExtensions());
        extensions.add(new NginxConfigExtension(nginxConfig));
        SwaggerExtensions.setExtensions(extensions);
    }


}
