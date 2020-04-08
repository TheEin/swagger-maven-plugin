package com.github.kongchen.swagger.docgen.mavenplugin;

import org.apache.maven.plugins.annotations.Parameter;

/**
 * Tag matching for nginx generated URLs
 */
public class NginxTag extends UrlMatchGroup {

    /**
     * Name of the tag
     */
    @Parameter(required = true)
    private String name;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
