package com.github.kongchen.swagger.docgen.nginx;

import com.github.kongchen.swagger.docgen.mavenplugin.Exclude;
import com.github.kongchen.swagger.docgen.mavenplugin.NginxConfig;
import com.github.kongchen.swagger.docgen.mavenplugin.NginxRewrite;
import com.github.kongchen.swagger.docgen.mavenplugin.NginxTag;
import com.github.kongchen.swagger.docgen.mavenplugin.UrlMatchGroup;
import com.github.kongchen.swagger.docgen.reader.JaxrsReader;
import com.github.odiszapc.nginxparser.NgxConfig;
import io.swagger.models.Operation;
import io.swagger.models.Swagger;
import io.swagger.models.Tag;
import org.apache.maven.plugin.logging.Log;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class NginxJaxrsReader extends JaxrsReader {

    private static class UrlTag {

        public final Pattern url;
        public final String name;

        public UrlTag(Pattern url, String name) {
            this.url = url;
            this.name = name;
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(NginxJaxrsReader.class);

    public static final String OPERATION_ID_FORMAT = "{{className}}.{{methodName}}";

    private final NgxConfig config;

    private final List<NginxRewrite> additionalRewrites;

    private final List<UrlMatchGroup> tags;

    private final List<UrlTag> urlTags;

    public NginxJaxrsReader(Swagger swagger, NginxConfig nginxConfig, Log log) {
        super(swagger, log);

        if (nginxConfig == null || !nginxConfig.isEnabled()) {
            config = null;
            additionalRewrites = null;
            tags = null;
            urlTags = null;
        } else {
            try {
                DirectoryStream.Filter<Path> excludeFilter = createFilter(nginxConfig.getExcludeLocations());
                NginxConfigReader reader = new NginxConfigReader(excludeFilter, nginxConfig.getProperties());
                config = reader.read(nginxConfig.getLocation());
                additionalRewrites = nginxConfig.getAdditionalRewrites();
                tags = nginxConfig.getTags();
                urlTags = createUrlTags(tags);
            } catch (IOException e) {
                throw new RuntimeException("Failed to load config", e);
            }
        }
    }

    private static DirectoryStream.Filter<Path> createFilter(List<String> locations) {
        if (locations == null) {
            return null;
        }
        return path -> {
            for (String location : locations) {
                if (path.endsWith(location)) {
                    return true;
                }
            }
            return false;
        };
    }

    private static List<UrlTag> createUrlTags(List<UrlMatchGroup> tags) {
        return Optional.ofNullable(tags).map(Collection::stream).map(stream ->
                stream.flatMap(tag -> Optional.ofNullable(tag.getUrls()).map(Collection::stream).map(urls ->
                        urls.filter(Objects::nonNull).map(url -> {
                            String name;
                            if (tag instanceof NginxTag) {
                                name = ((NginxTag) tag).getName();
                            } else if (tag instanceof Exclude) {
                                name = null;
                            } else {
                                throw new IllegalArgumentException("Unknown tag group type: " + tag.getClass());
                            }
                            return new UrlTag(Pattern.compile(url), name);
                        }))
                        .orElseGet(Stream::empty)).collect(Collectors.toList()))
                .orElseGet(Collections::emptyList);
    }

    @Override
    public void read(Set<Class<?>> classes) {
        if (getOperationIdFormat() == null) {
            setOperationIdFormat(OPERATION_ID_FORMAT);
        }

        super.read(classes);
        if (tags != null) {
            tags.stream()
                    .filter(NginxTag.class::isInstance)
                    .map(NginxTag.class::cast)
                    .map(NginxTag::getName)
                    .filter(name -> swagger.getTag(name) == null)
                    .forEach(name -> swagger.addTag(new Tag().name(name)));
        }
    }

    @Override
    protected io.swagger.models.Path updatePath(OperationContext<Class<?>> op) {
        String path = revertPath(op.path, op.httpMethod, op.operation);
        if (path == null) {
            return null;
        }
        op.path = path;
        return super.updatePath(op);
    }

    private String revertPath(String operationPath, String httpMethod, Operation operation) {
        try {
            if (config == null) {
                return operationPath;
            }
            String revertedPath =
                    new NginxLocationReverser(config, additionalRewrites, operationPath, httpMethod, operation)
                            .process();
            if (!revertedPath.equals(operationPath)) {
                String rewrittenPath =
                        new NginxLocationRewriter(config, additionalRewrites, revertedPath, httpMethod, operation)
                                .process();
                if (!rewrittenPath.equals(operationPath)) {
                    revertedPath = operationPath;
                }
            }
            if (urlTags != null) {
                for (UrlTag tag : urlTags) {
                    if (tag.url.matcher(revertedPath).matches()) {
                        if (tag.name == null) {
                            if (LOGGER.isDebugEnabled()) {
                                LOGGER.debug("Exclude matched URL {}", tag.url.pattern());
                            }
                            return null;
                        }
                        if (LOGGER.isInfoEnabled()) {
                            LOGGER.info("Set tag {} by matched URL {}", tag.name, tag.url.pattern());
                        }
                        operation.setTags(Collections.singletonList(tag.name));
                        break;
                    }
                }
            }
            return revertedPath;
        } catch (Exception e) {
            throw new RuntimeException("Failed to revert path: "
                    + Optional.ofNullable(httpMethod)
                    .map(String::toUpperCase).orElse("NULL")
                    + Optional.ofNullable(operationPath)
                    .map(s -> " " + s).orElse("")
                    + Optional.ofNullable(operation)
                    .map(Operation::getOperationId).map(s -> ", operationId = " + s).orElse(""),
                    e);
        }
    }
}
