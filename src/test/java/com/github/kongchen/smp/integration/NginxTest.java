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
import java.util.HashMap;

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
            HashMap<String, String> properties = new HashMap<>();
            properties.put("CAM_HOSTS", "127.0.0.1:26000");
            properties.put("CCMP_BACKEND_HOSTS", "127.0.0.1:26001");
            properties.put("CCM_HOSTS", "127.0.0.1:8888");
            properties.put("CDI_RTK_HOSTS", "127.0.0.1:8888");
            properties.put("CIM_HOSTS", "127.0.0.1:8888");
            properties.put("COMPOSITE_HOSTS", "127.0.0.1:8888");
            properties.put("COMPROV_HOSTS", "127.0.0.1:8888");
            properties.put("COMSLA_HOSTS", "127.0.0.1:8888");
            properties.put("COMSTR_HOSTS", "127.0.0.1:26001");
            properties.put("COMSTR_INT_HOSTS", "127.0.0.1:26011");
            properties.put("COM_CHAT_HOSTS", "127.0.0.1:8888");
            properties.put("COM_HOSTS", "127.0.0.1:8888");
            properties.put("COM_PLAN_HOSTS", "127.0.0.1:8888");
            properties.put("COM_SOAP_HOSTS", "127.0.0.1:8888");
            properties.put("COM_TMS_HOSTS", "127.0.0.1:8888");
            properties.put("CPM_RTK_HOSTS", "127.0.0.1:8889");
            properties.put("FILE_STORAGE_HOSTS", "127.0.0.1:8888");
            properties.put("OUT_FE", "127.0.0.1:8888");
            properties.put("OUT_SSO", "127.0.0.1:8888");
            properties.put("SDM_HOSTS", "127.0.0.1:8888");
            properties.put("SFA_HOSTS", "127.0.0.1:8888");
            nginxConfig.setProperties(properties);
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
