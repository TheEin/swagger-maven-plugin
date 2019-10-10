package com.github.kongchen.smp.integration;

import com.github.kongchen.swagger.docgen.mavenplugin.ApiDocumentMojo;
import com.github.kongchen.swagger.docgen.mavenplugin.ApiSource;
import com.github.kongchen.swagger.docgen.mavenplugin.NginxConfig;
import org.apache.commons.io.FileUtils;
import org.apache.maven.plugin.testing.AbstractMojoTestCase;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;

public class NginxTest extends AbstractMojoTestCase {

    private final File swaggerOutputDir = new File(getBasedir(), "target/test/nginx");

    private ApiDocumentMojo mojo;

    @Override
    @BeforeMethod
    protected void setUp() throws Exception {
        super.setUp();

        try {
            FileUtils.deleteDirectory(swaggerOutputDir);
        } catch (Exception ignore) {
        }

        File testPom = new File("C:\\src\\rt\\tailored_crm_b2b_rt_hq\\composite-be\\composite-api\\pom.xml");
        mojo = (ApiDocumentMojo) lookupMojo("generate", testPom);
        for (ApiSource apiSource : mojo.getApiSources()) {
            apiSource.setSwaggerDirectory(swaggerOutputDir.getAbsolutePath());
            final NginxConfig nginxConfig = new NginxConfig();
            nginxConfig.setLocation("C:\\src\\rt\\tailored_crm_b2b_rt_hq\\balancer\\nginx\\nginx.conf");
            apiSource.setNginxConfig(nginxConfig);
        }
    }

    @Override
    @AfterMethod
    protected void tearDown() throws Exception {
        super.tearDown();
    }

    @Test
    public void testGeneratedDoc() throws Exception {
        mojo.execute();
    }
}
