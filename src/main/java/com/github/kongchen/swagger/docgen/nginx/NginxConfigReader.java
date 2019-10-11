package com.github.kongchen.swagger.docgen.nginx;

import com.github.odiszapc.nginxparser.NgxConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Scanner;

public class NginxConfigReader {

    private static final Logger LOGGER = LoggerFactory.getLogger(NginxConfigReader.class);

    private static String readContent(String path) throws IOException {
        try (InputStream file = new FileInputStream(path)) {
            return readContent(file);
        }
    }

    private static String readContent(InputStream inputStream) throws IOException {
        Scanner scanner = new Scanner(inputStream).useDelimiter("\\A");
        String content = scanner.next();
        IOException e = scanner.ioException();
        if (e != null) {
            throw e;
        }
        return content;
    }

    private final DjangoTemplate djangoTemplate;

    private final String context;

    public NginxConfigReader(Map<String, String> context) {
        djangoTemplate = new DjangoTemplate();
        this.context = DjangoTemplate.buildContext(context);
    }

    public NgxConfig read(String path) throws IOException {
        LOGGER.debug("Reading config: {}", path);
        Path dir = Paths.get(path).getParent();
        try {
            String content = djangoTemplate.render(readContent(path), context);
            try (InputStream input = new ByteArrayInputStream(content.getBytes())) {
                NgxConfig config = NgxConfig.read(input);
                NginxBlockResolver<NgxConfig> resolver = new NginxBlockResolver<>(this, dir, config);
                return resolver.resolve();
            }
        } catch (IOException e) {
            throw new IOException("Failed to read config: " + path, e);
        }
    }
}
