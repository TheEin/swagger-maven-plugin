package com.github.kongchen.swagger.docgen.mavenplugin;

import org.apache.maven.plugins.annotations.Parameter;

/**
 * nginx rewrite
 */
public class NginxRewrite {

    @Parameter(required = true)
    private String regex;

    @Parameter(required = true)
    private String replace;

    public NginxRewrite() {
    }

    public NginxRewrite(String regex, String replace) {
        this.regex = regex;
        this.replace = replace;
    }

    public String getRegex() {
        return regex;
    }

    public void setRegex(String regex) {
        this.regex = regex;
    }

    public String getReplace() {
        return replace;
    }

    public void setReplace(String replace) {
        this.replace = replace;
    }
}
