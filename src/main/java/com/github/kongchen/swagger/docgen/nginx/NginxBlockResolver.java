package com.github.kongchen.swagger.docgen.nginx;

import com.github.odiszapc.nginxparser.NgxBlock;
import com.github.odiszapc.nginxparser.NgxConfig;
import com.github.odiszapc.nginxparser.NgxEntry;
import com.github.odiszapc.nginxparser.NgxParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves imports
 *
 * @param <T>
 */
public class NginxBlockResolver<T extends NgxBlock> {

    private static final Logger LOGGER = LoggerFactory.getLogger(NginxBlockResolver.class);

    private static final String INCLUDE = "include";

    private static final Pattern ABSOLUTE_PATH = Pattern.compile("^(/|[A-Z]:\\\\)");

    private static final Pattern RELATIVE_PATH = Pattern.compile("(.*[/\\\\])([^/\\\\]+)$");

    private final NginxConfigReader reader;

    private final Path dir;

    private final T block;

    private NginxBlockIterator<NgxBlock> iterator;

    private Deque<NginxBlockIterator<NgxBlock>> steps = new LinkedList<>();

    public NginxBlockResolver(NginxConfigReader reader, Path dir, T block) {
        this.reader = reader;
        this.dir = dir;
        this.block = block;
    }

    public T resolve() throws IOException {
        iterator = new NginxBlockIterator<>(block);
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
            iterator.close();
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
        if (ABSOLUTE_PATH.matcher(value).find()) {
            LOGGER.debug("Removing absolute include directive: {}", value);
            iterator.remove();
        } else {
            Matcher matcher = RELATIVE_PATH.matcher(value);
            if (!matcher.matches()) {
                throw new IOException("Failed to match relative path: " + value);
            }
            LOGGER.debug("Resolving relative include directive: {}", value);
            String relative = matcher.group(1);
            String filter = matcher.group(2);
            try (DirectoryStream<Path> paths = Files.newDirectoryStream(dir.resolve(relative), filter)) {
                ArrayList<NgxConfig> includes = new ArrayList<>();
                for (Path path : paths) {
                    if (reader.getExcludeFilter().accept(path)) {
                        LOGGER.debug("Skipping include: {}", path);
                        continue;
                    }
                    NgxConfig config = reader.read(path.toString());
                    NginxBlockResolver<NgxConfig> resolver = new NginxBlockResolver<>(reader, path.getParent(), config);
                    includes.add(resolver.resolve());
                }
                if (includes.isEmpty()) {
                    LOGGER.warn("None files matched: {}/{}", dir, value);
                    iterator.remove();
                } else if (includes.size() == 1) {
                    iterator.replace(includes.get(0));
                } else {
                    NgxBlock includesBlock = new NgxBlock();
                    includesBlock.addValue(INCLUDE);
                    includes.forEach(includesBlock::addEntry);
                    iterator.replace(includesBlock);
                }
            }
        }
    }
}
