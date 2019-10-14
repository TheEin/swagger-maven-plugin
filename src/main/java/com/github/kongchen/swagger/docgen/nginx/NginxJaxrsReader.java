package com.github.kongchen.swagger.docgen.nginx;

import com.github.kongchen.swagger.docgen.mavenplugin.NginxConfig;
import com.github.kongchen.swagger.docgen.mavenplugin.NginxRewrite;
import com.github.kongchen.swagger.docgen.reader.JaxrsReader;
import com.github.odiszapc.nginxparser.NgxConfig;
import io.swagger.models.Operation;
import io.swagger.models.Swagger;
import org.apache.maven.plugin.logging.Log;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public class NginxJaxrsReader extends JaxrsReader {

    private final NgxConfig config;

    private final List<NginxRewrite> additionalRewrites;

    public NginxJaxrsReader(Swagger swagger, NginxConfig nginxConfig, Log LOG) {
        super(swagger, LOG);

        if (nginxConfig == null) {
            config = null;
            additionalRewrites = null;
        } else {
            try {
                DirectoryStream.Filter<Path> excludeFilter = nginxConfig.getExcludeLocations() == null ? null :
                        path -> {
                            for (String location : nginxConfig.getExcludeLocations()) {
                                if (path.endsWith(location)) {
                                    return true;
                                }
                            }
                            return false;
                        };
                NginxConfigReader reader = new NginxConfigReader(excludeFilter, nginxConfig.getProperties());
                config = reader.read(nginxConfig.getLocation());
                additionalRewrites = nginxConfig.getAdditionalRewrites();
            } catch (IOException e) {
                throw new RuntimeException("Failed to load config", e);
            }
        }
    }

    @Override
    protected void updatePath(String operationPath, String httpMethod, Operation operation) {
        operationPath = revertPath(operationPath, httpMethod, operation);
        super.updatePath(operationPath, httpMethod, operation);
    }

    private String revertPath(String operationPath, String httpMethod, Operation operation) {
        try {
            if (config == null) {
                return operationPath;
            }
            return new NginxLocationRewriter(config, additionalRewrites, operationPath, httpMethod, operation).revertPath();
        } catch (Exception e) {
            throw new RuntimeException("Failed to revert path: "
                    + Optional.ofNullable(httpMethod)
                    .map(String::toUpperCase).orElse("NULL")
                    + Optional.ofNullable(operationPath)
                    .map(s -> " " + s).orElse("")
                    + Optional.ofNullable(operation)
                    .map(Operation::getOperationId).map(s -> ", operationId = " + s).orElse(""),
                    e);
        }
    }
}
