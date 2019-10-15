package com.github.kongchen.swagger.docgen.mavenplugin;

import org.apache.maven.plugins.annotations.Parameter;

import java.util.List;

public class NginxTag {

    /**
     * Name of the tag
     */
    @Parameter(required = true)
    private String name;

    /**
     * URL regex list to match the tag
     */
    @Parameter(required = true)
    private List<String> urls;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<String> getUrls() {
        return urls;
    }

    public void setUrls(List<String> urls) {
        this.urls = urls;
    }
}
