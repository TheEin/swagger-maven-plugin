package com.github.kongchen.swagger.docgen.nginx;

import com.github.kongchen.swagger.docgen.mavenplugin.NginxRewrite;
import com.github.odiszapc.nginxparser.NgxConfig;
import io.swagger.models.Operation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NginxLocationReverser extends NginxLocationProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(NginxLocationReverser.class);

    private static final Pattern ID_REGEX = Pattern.compile("\\\\d\\+|\\[\\^/]\\+");
    private static final Pattern REPLACE_GROUP = Pattern.compile("\\$(\\d+)");

    private final List<RewriteParams> unconditionalRewrites = new ArrayList<>();

    public NginxLocationReverser(NgxConfig config, List<NginxRewrite> additionalRewrites, String path, String httpMethod, Operation operation) {
        super(config, path, httpMethod, operation);
        if (additionalRewrites != null) {
            additionalRewrites.stream()
                    .map(RewriteParams::new)
                    .map(NginxLocationReverser::revertGlobalRewrite)
                    .forEachOrdered(unconditionalRewrites::add);
        }
    }

    @Override
    public String process() {
        String revertedPath = super.process();
        revertedPath = applyLocationRewrites(revertedPath);
        Pattern notFoundLocation = searchNotFoundLocations(revertedPath);
        if (notFoundLocation != null) {
            revertedPath = path;
        } else {
            revertedPath = applyUnconditionalRewrites(revertedPath);
        }
        LOGGER.info("Reverted path: {}", revertedPath);
        return revertedPath;
    }

    private String applyUnconditionalRewrites(String path) {
        String revertedPath = path;
        ListIterator<RewriteParams> it = unconditionalRewrites.listIterator(unconditionalRewrites.size());
        while (it.hasPrevious()) {
            RewriteParams rewrite = it.previous();
            revertedPath = applyUnconditionalRewrite(rewrite, revertedPath);
        }
        return revertedPath;
    }

    @Override
    protected String identifyLocation() {
        String regex = super.identifyLocation();
        return ID_REGEX.matcher(regex).replaceAll(ID_MARK)
                .replace("/", "\\/");
    }

    @Override
    protected RewriteParams identifyRewrite(RewriteParams rewrite) {
        return revertRewrite(locationUrl, rewrite);
    }

    @Override
    protected boolean matchRewrite(RewriteParams rewrite) {
        Matcher matcher = Pattern
                .compile(rewrite.regex.replace("/", "\\/"))
                .matcher(markedPath);
        if (!matcher.matches()) {
            LOGGER.debug("Rewrite wasn't matched: {}", rewrite);
        } else {
            LOGGER.debug("Rewrite was matched: {}", rewrite);
            String result;
            if (matcher.groupCount() == 0) {
                result = rewrite.replace;
            } else {
                StringBuffer sb = new StringBuffer();
                matcher.appendReplacement(sb, rewrite.replace).appendTail(sb);
                result = sb.toString();
            }
            LOGGER.debug("Result URL: {}", result);
            matcher = locationRegex.matcher(result);
            if (!matcher.matches()) {
                LOGGER.debug("Path wasn't matched");
            } else {
                LOGGER.debug("Path was matched");
                return true;
            }
        }
        return false;
    }

    private static RewriteParams revertRewrite(String locationUrl, RewriteParams rewrite) {
        List<Integer> replaceGroups = new ArrayList<>();
        String regex = revertReplace(rewrite.replace, replaceGroups);
        String replace;
        if (replaceGroups.isEmpty()) {
            if (locationUrl == null) {
                throw new IllegalStateException("Constant rewrite without location");
            }
            if (locationUrl.indexOf('(') >= 0) {
                throw new IllegalStateException("Replace and location both should be constant");
            }
            replace = locationUrl;
        } else {
            replace = revertRegex(rewrite.regex, replaceGroups);
        }
        return new RewriteParams(regex, replace, rewrite.opt);
    }

    private static String revertReplace(String replace, List<Integer> replaceGroups) {
        StringBuffer sb = new StringBuffer();
        Matcher matcher = REPLACE_GROUP.matcher(replace);
        while (matcher.find()) {
            matcher.appendReplacement(sb, "(.*)");
            String val = matcher.group(1);
            replaceGroups.add(Integer.parseInt(val));
        }
        if (replaceGroups.isEmpty()) {
            LOGGER.debug("Constant replace: {}", replace);
            return replace;
        }
        matcher.appendTail(sb);
        LOGGER.debug("Reverted replace {} with groups {}", sb, replaceGroups);
        return sb.toString();
    }

    private static String revertRegex(String regex, List<Integer> replaceGroups) {
        int lb = regex.indexOf('(');
        if (lb < 0) {
            throw new IllegalStateException("Not a regex: " + regex);
        }
        StringBuilder sb = new StringBuilder();
        int rb = 0;
        int matchIdx = 1;
        do {
            if (lb > rb) {
                sb.append(regex, rb, lb);
            }
            int deep = 0;
            for (rb = lb + 1; rb < regex.length(); ++rb) {
                int c = regex.charAt(rb);
                if (c == ')') {
                    --deep;
                    if (deep < 0) {
                        break;
                    }
                } else if (c == '(') {
                    ++deep;
                }
            }
            if (rb == regex.length()) {
                throw new IllegalStateException("Unfinished group");
            }
            int groupIdx = replaceGroups.indexOf(matchIdx);
            if (groupIdx < 0) {
                throw new IllegalStateException("Replace group not found for match index " + matchIdx);
            }
            sb.append('$').append(++groupIdx);
            lb = regex.indexOf('(', ++rb);
        } while (lb > 0);
        if (rb < regex.length()) {
            sb.append(regex, rb, regex.length());
        }
        normalizeRegex(sb);
        LOGGER.debug("Reverted regex {}", sb);
        return sb.toString();
    }

    private static RewriteParams revertGlobalRewrite(RewriteParams rewrite) {
        return revertRewrite(null, rewrite);
    }

    @Override
    protected void unconditionalRewrite(RewriteParams rewrite) {
        unconditionalRewrites.add(rewrite);
    }
}
