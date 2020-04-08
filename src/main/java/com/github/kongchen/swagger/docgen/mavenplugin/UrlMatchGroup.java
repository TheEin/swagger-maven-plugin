package com.github.kongchen.swagger.docgen.mavenplugin;

import org.apache.maven.plugins.annotations.Parameter;

import java.util.List;

/**
 * URL matching group
 */
public abstract class UrlMatchGroup {

    /**
     * URL regex list
     */
    @Parameter(required = true)
    private List<String> urls;

    public List<String> getUrls() {
        return urls;
    }

    public void setUrls(List<String> urls) {
        this.urls = urls;
    }
}
