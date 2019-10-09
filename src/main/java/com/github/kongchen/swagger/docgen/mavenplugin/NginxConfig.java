package com.github.kongchen.swagger.docgen.mavenplugin;

import org.apache.maven.plugins.annotations.Parameter;

/**
 * nginx specific options
 */
public class NginxConfig {

    /**
     * Path to <b>nginx.conf</b>
     */
    @Parameter(required = true)
    private String location;

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }
}
