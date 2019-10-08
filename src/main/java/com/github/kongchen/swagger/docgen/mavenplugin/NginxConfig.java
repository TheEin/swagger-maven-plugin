package com.github.kongchen.swagger.docgen.mavenplugin;

import org.apache.maven.plugins.annotations.Parameter;

import java.util.List;

/**
 * nginx specific options
 */
public class NginxConfig {

    /**
     * Paths to nginx configurations containing location rewrite rules
     */
    @Parameter(required = true)
    private List<String> locations;
}
