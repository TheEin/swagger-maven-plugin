package com.github.kongchen.smp.integration;

import com.github.kongchen.swagger.docgen.mavenplugin.ApiDocumentMojo;
import com.github.kongchen.swagger.docgen.mavenplugin.ApiSource;
import com.github.kongchen.swagger.docgen.mavenplugin.NginxConfig;
import com.github.kongchen.swagger.docgen.mavenplugin.NginxRewrite;
import org.apache.commons.io.FileUtils;
import org.apache.maven.plugin.testing.AbstractMojoTestCase;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class NginxTest extends AbstractMojoTestCase {

    private final Pattern NL_DELIMITER = Pattern.compile("\n");

    private final File swaggerOutputDir = new File(getBasedir(), "target/test/nginx");

    private Properties properties;

    private Path projectRoot;

    private File testPom;

    private ClassLoader testClassLoader;

    @Override
    @BeforeMethod
    protected void setUp() throws Exception {
        super.setUp();

        try {
            FileUtils.deleteDirectory(swaggerOutputDir);
        } catch (Exception ignore) {
        }
        properties = new Properties();
        try (InputStream propertiesStream = getClass().getResourceAsStream(
                getClass().getSimpleName() + ".properties")) {
            properties.load(propertiesStream);
        }

        String projectPath = properties.getProperty("PROJECT_PATH");
        assertNotNull("Project path was not specified", projectPath);
        projectRoot = Paths.get(projectPath);
        testPom = projectRoot.resolve(properties.getProperty("POM_PATH")).toFile();
        String classPath = properties.getProperty("CLASS_PATH");
        testClassLoader = getClassLoader();
        Optional.ofNullable(classPath).ifPresent(cp -> {
            URL[] urls = NL_DELIMITER.splitAsStream(cp).map(path -> {
                try {
                    return projectRoot.resolve(path).toUri().toURL();
                } catch (MalformedURLException e) {
                    throw new RuntimeException(e);
                }
            }).toArray(URL[]::new);
            Thread.currentThread().setContextClassLoader(new URLClassLoader(urls, testClassLoader));
        });
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> toMap(Properties properties) {
        return (Map) properties;
    }

    @Override
    @AfterMethod
    protected void tearDown() throws Exception {
        Thread.currentThread().setContextClassLoader(testClassLoader);
        super.tearDown();
    }

    @Test
    public void testGeneratedDoc() throws Exception {
        ApiDocumentMojo mojo;
        try {
            mojo = (ApiDocumentMojo) lookupMojo("generate", testPom);
        } catch (Exception e) {
            System.err.println("test generate failed: " + e);
            return;
        }
        Map<Object, Object> ctx = new HashMap<>();
        mojo.setPluginContext(ctx);
        List<ApiSource> apiSources = new ArrayList<>();
        for (ApiSource apiSource : mojo.getApiSources()) {
            if (apiSource.getLocations().stream().anyMatch(s -> s.contains("${"))) {
                continue;
            }
            apiSources.add(apiSource);
            apiSource.setSwaggerDirectory(swaggerOutputDir.getAbsolutePath());
            NginxConfig nginxConfig = apiSource.getNginxConfig();
            if (nginxConfig == null) {
                continue;
            }
            nginxConfig.setLocation(projectRoot.resolve(properties.getProperty("CFG_PATH")).toAbsolutePath().toString());
            Optional.ofNullable(properties.getProperty("ADD_REWRITES"))
                    .ifPresent(property -> {
                        String[] args = NL_DELIMITER.split(property);
                        List<NginxRewrite> rewrites = new ArrayList<>();
                        for (int i = 0; i < args.length; ) {
                            String r1 = args[i++];
                            String r2 = args[i++];
                            rewrites.add(new NginxRewrite(r1, r2));
                        }
                        nginxConfig.setAdditionalRewrites(rewrites);
                    });
            Optional.ofNullable(properties.getProperty("EXCLUDE_LOCATIONS"))
                    .ifPresent(property ->
                            nginxConfig.setExcludeLocations(NL_DELIMITER
                                    .splitAsStream(property)
                                    .collect(Collectors.toList())));
            Map<String, String> nginxProperties = new HashMap<>(nginxConfig.getProperties());
            nginxProperties.putAll(toMap(properties));
            nginxConfig.setProperties(nginxProperties);
            apiSource.setNginxConfig(nginxConfig);
        }
        mojo.setApiSources(apiSources);
        mojo.execute();
    }
}
