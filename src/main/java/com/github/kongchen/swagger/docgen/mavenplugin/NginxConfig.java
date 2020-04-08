package com.github.kongchen.swagger.docgen.mavenplugin;

import org.apache.maven.plugins.annotations.Parameter;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * nginx specific options
 */
public class NginxConfig implements Cloneable {

    /**
     * Is nginx config enabled
     */
    @Parameter(defaultValue = "true")
    private boolean enabled = true;

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
    private List<UrlMatchGroup> tags;

    /**
     * Templating properties
     */
    @Parameter
    private Map<String, String> properties;

    @Override
    public NginxConfig clone() {
        try {
            return (NginxConfig) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException(e);
        }
    }

    public NginxConfig updateBy(NginxConfig config) {
        enabled = config.enabled;
        Optional.ofNullable(config.location).ifPresent(this::setLocation);
        Optional.ofNullable(config.excludeLocations).ifPresent(this::setExcludeLocations);
        Optional.ofNullable(config.additionalRewrites).ifPresent(this::setAdditionalRewrites);
        Optional.ofNullable(config.tags).ifPresent(this::setTags);
        Optional.ofNullable(config.properties).ifPresent(this::setProperties);
        return this;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
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

    public List<UrlMatchGroup> getTags() {
        return tags;
    }

    public void setTags(List<UrlMatchGroup> tags) {
        this.tags = tags;
    }

    public Map<String, String> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, String> properties) {
        this.properties = properties;
    }
}
