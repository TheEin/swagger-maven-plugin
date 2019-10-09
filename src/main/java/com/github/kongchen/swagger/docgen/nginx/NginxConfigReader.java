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
import java.util.Collections;
import java.util.Scanner;

public class NginxConfigReader implements AutoCloseable {

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

    public NginxConfigReader() throws IOException {
        djangoTemplate = new DjangoTemplate();
        context = DjangoTemplate.buildContext(Collections.emptyMap());
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

    @Override
    public void close() {
        djangoTemplate.close();
    }
}
