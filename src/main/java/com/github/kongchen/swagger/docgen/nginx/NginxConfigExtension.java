package com.github.kongchen.swagger.docgen.nginx;

import com.github.kongchen.swagger.docgen.mavenplugin.NginxConfig;
import com.github.odiszapc.nginxparser.NgxConfig;
import io.swagger.jaxrs.ext.AbstractSwaggerExtension;

import java.io.IOException;

public class NginxConfigExtension extends AbstractSwaggerExtension {

    private final NgxConfig config;

    public static void main(String[] args) {
        NginxConfig nginxConfig = new NginxConfig();
        nginxConfig.setLocation(args[0]);
        NginxConfigExtension nginxConfigExtension = new NginxConfigExtension(nginxConfig);
        System.out.println(nginxConfigExtension.config);
    }

    public NginxConfigExtension(NginxConfig nginxConfig) {
        try (NginxConfigReader reader = new NginxConfigReader()) {
            config = reader.read(nginxConfig.getLocation());
        } catch (IOException e) {
            throw new RuntimeException("Failed to load config", e);
        }
    }
}
