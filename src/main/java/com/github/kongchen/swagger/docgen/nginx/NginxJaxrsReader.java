package com.github.kongchen.swagger.docgen.nginx;

import com.github.kongchen.swagger.docgen.mavenplugin.NginxConfig;
import com.github.kongchen.swagger.docgen.reader.JaxrsReader;
import com.github.odiszapc.nginxparser.NgxConfig;
import io.swagger.models.Operation;
import io.swagger.models.Swagger;
import org.apache.maven.plugin.logging.Log;

import java.io.IOException;

public class NginxJaxrsReader extends JaxrsReader {

    private final NgxConfig config;

    public NginxJaxrsReader(Swagger swagger, NginxConfig nginxConfig, Log LOG) {
        super(swagger, LOG);

        if (nginxConfig == null) {
            config = null;
        } else {
            try {
                NginxConfigReader reader = new NginxConfigReader();
                config = reader.read(nginxConfig.getLocation());
            } catch (IOException e) {
                throw new RuntimeException("Failed to load config", e);
            }
        }
    }

    @Override
    protected void updatePath(String operationPath, String httpMethod, Operation operation) {
        operationPath = revertPath(operationPath, httpMethod);
        super.updatePath(operationPath, httpMethod, operation);
    }

    private String revertPath(String operationPath, String httpMethod) {
        if (config == null) {
            return operationPath;
        }

        return new NginxLocationRewriter(config, operationPath, httpMethod)
                .revertPath();
    }
}
