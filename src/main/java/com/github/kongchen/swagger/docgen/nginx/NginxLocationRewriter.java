package com.github.kongchen.swagger.docgen.nginx;

import com.github.kongchen.swagger.docgen.mavenplugin.NginxRewrite;
import com.github.odiszapc.nginxparser.NgxConfig;
import io.swagger.models.Operation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.regex.Pattern;

public class NginxLocationRewriter extends NginxLocationProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(NginxLocationRewriter.class);

    private static String applyUnconditionalRewrites(List<NginxRewrite> additionalRewrites, String path) {
        for (NginxRewrite additionalRewrite : additionalRewrites) {
            path = applyUnconditionalRewrite(new RewriteParams(additionalRewrite), path);
        }
        return path;
    }

    protected NginxLocationRewriter(NgxConfig config, List<NginxRewrite> additionalRewrites, String path, String httpMethod, Operation operation) {
        super(config, applyUnconditionalRewrites(additionalRewrites, path), httpMethod, operation);
    }

    @Override
    public String process() {
        String rewrittenPath = super.process();
        Pattern notFoundLocation = searchNotFoundLocations(rewrittenPath);
        if (notFoundLocation != null) {
            return "/404.html";
        }
        rewrittenPath = applyLocationRewrites(rewrittenPath);
        LOGGER.info("Rewritten path: {}", rewrittenPath);
        return rewrittenPath;
    }

    @Override
    protected String rewritePath(RewriteParams rewrite, String path, boolean optional) {
        String r = ID_REGEX.matcher(rewrite.regex).replaceAll(".*");
        RewriteParams p = new RewriteParams(r, rewrite.replace, rewrite.opt);
        return super.rewritePath(p, path, optional);
    }

    @Override
    protected String identifyLocation() {
        String regex = super.identifyLocation();
        return regex.replace("/", "\\/");
    }

    @Override
    protected RewriteParams identifyRewrite(RewriteParams rewrite) {
        return rewrite;
    }

    @Override
    protected boolean matchRewrite(RewriteParams rewrite) {
        return locationRegex.matcher(markedPath).matches();
    }

    @Override
    protected void unconditionalRewrite(RewriteParams rewrite) {
        String newMarkedPath = applyUnconditionalRewrite(rewrite, markedPath);
        if (!newMarkedPath.equals(markedPath)) {
            String newPath = applyUnconditionalRewrite(rewrite, path);
            if (newPath.equals(path)) {
                throw new IllegalStateException("Failed to unconditionally " + rewrite + " on path " + path);
            }
            markedPath = newMarkedPath;
            path = newPath;
        }
    }
}
