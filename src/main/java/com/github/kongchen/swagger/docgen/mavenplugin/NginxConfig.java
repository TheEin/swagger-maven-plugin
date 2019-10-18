package com.github.kongchen.swagger.docgen.mavenplugin;

import org.apache.maven.plugins.annotations.Parameter;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * nginx specific options
 */
public class NginxConfig {

    /**
     * Path to <b>nginx.conf</b>
     */
    @Parameter(required = true)
    private String location;

    @Parameter
    private List<String> excludeLocations;

    @Parameter
    private List<NginxRewrite> additionalRewrites;

    @Parameter
    private List<NginxTag> tags;

    /**
     * Templating properties
     */
    @Parameter
    private Map<String, String> properties;

    public NginxConfig() {
    }

    public NginxConfig(NginxConfig config) {
        location = config.location;
        excludeLocations = config.excludeLocations;
        additionalRewrites = config.additionalRewrites;
        tags = config.tags;
        properties = config.properties;
    }

    public void updateBy(NginxConfig config) {
        Optional.ofNullable(config.location).ifPresent(this::setLocation);
        Optional.ofNullable(config.excludeLocations).ifPresent(this::setExcludeLocations);
        Optional.ofNullable(config.additionalRewrites).ifPresent(this::setAdditionalRewrites);
        Optional.ofNullable(config.tags).ifPresent(this::setTags);
        Optional.ofNullable(config.properties).ifPresent(this::setProperties);
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public List<String> getExcludeLocations() {
        return excludeLocations;
    }

    public void setExcludeLocations(List<String> excludeLocations) {
        this.excludeLocations = excludeLocations;
    }

    public List<NginxRewrite> getAdditionalRewrites() {
        return additionalRewrites;
    }

    public void setAdditionalRewrites(List<NginxRewrite> additionalRewrites) {
        this.additionalRewrites = additionalRewrites;
    }

    public List<NginxTag> getTags() {
        return tags;
    }

    public void setTags(List<NginxTag> tags) {
        this.tags = tags;
    }

    public Map<String, String> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, String> properties) {
        this.properties = properties;
    }
}
