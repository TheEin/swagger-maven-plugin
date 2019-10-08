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


public class DjangoTemplate {

    private static final String RENDER_TEMPLATE_FILE = "renderTemplate.py";

    private PythonInterpreter py;

    public static String buildContext(Map<String, String> context) {
        return new PyStringMap(context.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> new PyString(e.getValue()))))
                .asString();
    }

    public DjangoTemplate() throws IOException {
        py = new PythonInterpreter();
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

    public String render(String template, String context) {
        py.set("template", new PyString(template));
        py.set("context", new PyString(context));
        return py.eval("renderTemplate(conf, {})").asString();
    }
}
