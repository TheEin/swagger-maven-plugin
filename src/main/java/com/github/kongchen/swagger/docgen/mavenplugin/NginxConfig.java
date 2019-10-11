package com.github.kongchen.swagger.docgen.mavenplugin;

import org.apache.maven.plugins.annotations.Parameter;

import java.util.Map;

/**
 * nginx specific options
 */
public class NginxConfig {

    /**
     * Path to <b>nginx.conf</b>
     */
    @Parameter(required = true)
    private String location;

    /**
     * Templating properties
     */
    @Parameter
    private Map<String, String> properties;

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public Map<String, String> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, String> properties) {
        this.properties = properties;
    }
}
