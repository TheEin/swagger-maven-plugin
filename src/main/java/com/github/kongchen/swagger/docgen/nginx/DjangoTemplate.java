package com.github.kongchen.swagger.docgen.nginx;

import org.python.core.PyString;
import org.python.core.PyStringMap;
import org.python.util.PythonInterpreter;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;


public class DjangoTemplate implements AutoCloseable {

    private static final String RENDER_TEMPLATE_FILE = "renderTemplate.py";

    private final PythonInterpreter py = createInterpreter();

    public static String buildContext(Map<String, String> context) {
        return new PyStringMap(context.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> new PyString(e.getValue()))))
                .toString();
    }

    public DjangoTemplate() throws IOException {
        try (InputStream input = DjangoTemplate.class
                .getClassLoader()
                .getResourceAsStream(RENDER_TEMPLATE_FILE);
             Reader reader = Optional
                     .ofNullable(input)
                     .map(InputStreamReader::new)
                     .orElseThrow(() -> new FileNotFoundException(RENDER_TEMPLATE_FILE))) {

            py.exec(py.compile(reader));
        } catch (IOException e) {
            throw new IOException("Error reading " + RENDER_TEMPLATE_FILE, e);
        }
    }

    @SuppressWarnings("squid:S2095")
    private PythonInterpreter createInterpreter() {
        return new PythonInterpreter();
    }

    public String render(String t, String ctx) {
        try {
            py.set("t", t);
            py.set("ctx", ctx);
            return py.eval("renderTemplate(t, ctx)").asString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to render template", e);
        }
    }

    @Override
    public void close() {
        py.close();
    }
}
