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

    private static final PythonInterpreter PY = createInterpreter();

    @SuppressWarnings("squid:S2095")
    private static PythonInterpreter createInterpreter() {
        final PythonInterpreter py = new PythonInterpreter();
        try (InputStream input = DjangoTemplate.class
                .getClassLoader()
                .getResourceAsStream(RENDER_TEMPLATE_FILE);
             Reader reader = Optional
                     .ofNullable(input)
                     .map(InputStreamReader::new)
                     .orElseThrow(() -> new FileNotFoundException(RENDER_TEMPLATE_FILE))) {

            py.exec(py.compile(reader));
        } catch (IOException e) {
            throw new RuntimeException("Error reading " + RENDER_TEMPLATE_FILE, e);
        }
        return py;
    }

    public static String buildContext(Map<String, String> context) {
        return Optional.ofNullable(context)
                .map(map -> new PyStringMap(map.entrySet().stream()
                        .collect(Collectors.toMap(
                                Map.Entry::getKey,
                                e -> new PyString(e.getValue())))))
                .orElse(new PyStringMap())
                .toString();
    }

    public String render(String t, String ctx) {
        try {
            PY.set("t", t);
            PY.exec("ctx = " + ctx);
            return PY.eval("renderTemplate(t, ctx)").asString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to render template", e);
        }
    }
}
