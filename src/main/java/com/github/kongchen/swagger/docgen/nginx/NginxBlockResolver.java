package com.github.kongchen.swagger.docgen.nginx;

import com.github.odiszapc.nginxparser.NgxBlock;
import com.github.odiszapc.nginxparser.NgxConfig;
import com.github.odiszapc.nginxparser.NgxEntry;
import com.github.odiszapc.nginxparser.NgxParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Deque;
import java.util.LinkedList;

/**
 * Resolves imports
 *
 * @param <T>
 */
public class NginxBlockResolver<T extends NgxBlock> {

    private static final Logger LOGGER = LoggerFactory.getLogger(NginxBlockResolver.class);

    private static final String INCLUDE = "include";

    private final NginxConfigReader reader;

    private final T block;

    private NginxBlockIterator<NgxBlock> iterator;

    private Deque<NginxBlockIterator<NgxBlock>> steps = new LinkedList<>();

    public NginxBlockResolver(NginxConfigReader reader, T block) {
        this.reader = reader;
        this.block = block;
        iterator = new NginxBlockIterator<>(block);
    }

    public T resolve() throws IOException {
        do {
            while (iterator.hasNext()) {
                NgxEntry entry = iterator.next();
                if (entry instanceof NgxBlock) {
                    steps.push(iterator);
                    iterator = new NginxBlockIterator<>((NgxBlock) entry);
                } else if (entry instanceof NgxParam) {
                    param((NgxParam) entry);
                }
            }
            iterator = steps.poll();
        } while (iterator != null);
        return block;
    }

    private void param(NgxParam param) throws IOException {
        if (param.getName().equals(INCLUDE)) {
            include(param.getValue());
        }
    }

    private void include(String value) throws IOException {
        Path path = Paths.get(value);
        if (path.isAbsolute()) {
            LOGGER.debug("Removing absolute include directive: {}", value);
            iterator.remove();
        } else {
            LOGGER.debug("Resolving relative include directive: {}", value);
            NgxConfig config = reader.read(path.toAbsolutePath().toString());
            NginxBlockResolver<NgxBlock> resolver = new NginxBlockResolver<>(reader, config);
            iterator.replace(resolver.resolve());
        }
    }

    private static class Step<T extends NgxBlock> {

        public Step(Step<NgxBlock> parent, NginxBlockIterator<T> iterator) {
            this.parent = parent;
            this.iterator = iterator;
        }

        Step<NgxBlock> parent;
        NginxBlockIterator<T> iterator;
    }
}
