package com.github.kongchen.swagger.docgen.nginx;

import com.github.kongchen.swagger.docgen.mavenplugin.NginxRewrite;
import com.github.odiszapc.nginxparser.NgxBlock;
import com.github.odiszapc.nginxparser.NgxConfig;
import com.github.odiszapc.nginxparser.NgxEntry;
import com.github.odiszapc.nginxparser.NgxIfBlock;
import com.github.odiszapc.nginxparser.NgxParam;
import io.swagger.models.Operation;
import org.apache.commons.lang3.RandomUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.github.kongchen.swagger.docgen.nginx.NginxDirective.BREAK;
import static com.github.kongchen.swagger.docgen.nginx.NginxDirective.LAST;
import static com.github.kongchen.swagger.docgen.nginx.NginxDirective.LOCATION;
import static com.github.kongchen.swagger.docgen.nginx.NginxDirective.REQUEST_METHOD;
import static com.github.kongchen.swagger.docgen.nginx.NginxDirective.REWRITE;

public class NginxLocationRewriter {

    private enum LocationType {
        PREFIX(null, true, false, false),
        NO_REGEX("^~", true, false, true),
        STRICT("=", false, false, true),
        REGEX_MATCH("~", false, true, false),
        IREGEX_MATCH("~*", false, true, false);

        private final String op;

        private final boolean prefix;

        private final boolean regex;

        private final boolean noRegex;

        LocationType(String op, boolean prefix, boolean regex, boolean noRegex) {
            this.op = op;
            this.prefix = prefix;
            this.regex = regex;
            this.noRegex = noRegex;
        }
    }

    private static class RewriteParams {

        public final String regex;
        public final String replace;
        public final String opt;

        public RewriteParams(String regex, String replace, String opt) {
            this.regex = regex;
            this.replace = replace;
            this.opt = opt;
        }

        public RewriteParams(NginxRewrite rewrite) {
            regex = rewrite.getRegex();
            replace = rewrite.getReplace();
            opt = null;
        }

        public RewriteParams(NgxParam rewrite) {
            Iterator<String> args = argsIterator(rewrite.getValues());

            regex = args.next();
            replace = args.next();
            opt = args.next();

            if (regex == null || replace == null) {
                throw new IllegalStateException("Useless rewrite");
            }
        }

        @Override
        public String toString() {
            return "rewrite " + regex + " " + replace + " " + opt;
        }
    }

    private static class FinishProcessing extends RuntimeException {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(NginxLocationRewriter.class);

    private static final String ID_REGEX = "\\d+";
    private static final Pattern PATH_ID = Pattern.compile("\\{\\w+}");
    private static final String ID_MARK = String.valueOf(RandomUtils.nextInt(1 << 30, Integer.MAX_VALUE));
    private static final Pattern REPLACE_GROUP = Pattern.compile("\\$(\\d+)");

    private final NgxConfig config;
    private final Operation operation;
    private final String path;
    private final String markedPath;
    private final String httpMethod;
    private final List<RewriteParams> unconditionalRewrites = new ArrayList<>();
    private final Deque<Iterator<NgxEntry>> steps = new LinkedList<>();

    private Iterator<NgxEntry> iterator;
    private NgxBlock location;
    private LocationType locationType;
    private String locationUrl;
    private String locationRegex;
    private Iterator<NgxEntry> locationIterator;
    private NgxBlock prefixLocation;
    private LocationType prefixLocationType;
    private String prefixLocationUrl;
    private RewriteParams prefixRewrite;
    private NgxBlock regexLocation;
    private RewriteParams regexRewrite;

    private static <T> Iterator<T> argsIterator(Iterable<T> source) {
        Iterator<T> inner = source.iterator();

        return new Iterator<T>() {
            @Override
            public boolean hasNext() {
                return inner.hasNext();
            }

            @Override
            public T next() {
                return inner.hasNext() ? inner.next() : null;
            }
        };
    }

    public NginxLocationRewriter(NgxConfig config, List<NginxRewrite> additionalRewrites, String path, String httpMethod, Operation operation) {
        if (path == null) {
            throw new NullPointerException("operationPath");
        }
        if (httpMethod == null) {
            throw new IllegalArgumentException("HTTP method can't be null");
        }
        this.config = config;
        this.operation = operation;
        this.path = path;
        markedPath = PATH_ID.matcher(path).replaceAll(ID_MARK);
        this.httpMethod = httpMethod.toUpperCase();
        if (additionalRewrites != null) {
            additionalRewrites.stream()
                    .map(RewriteParams::new)
                    .map(NginxLocationRewriter::revertGlobalRewrite)
                    .forEachOrdered(unconditionalRewrites::add);
        }
    }

    public String revertPath() {
        LOGGER.info("Reverting {} {}, operationId = {}", httpMethod, path, operation.getOperationId());
        iterator = config.iterator();
        try {
            do {
                while (iterator.hasNext()) {
                    entry(iterator.next());
                }
                stepOut();
            } while (iterator != null);
        } catch (FinishProcessing ignore) {
            // use locations were found
        }
        String revertedPath = path;
        if (prefixLocation != null && prefixLocationType.noRegex) {
            revertedPath = revertPath(revertedPath, prefixRewrite, prefixLocation, false);
        } else if (regexLocation != null) {
            revertedPath = revertPath(revertedPath, regexRewrite, regexLocation, false);
        } else if (prefixLocation != null) {
            revertedPath = revertPath(revertedPath, prefixRewrite, prefixLocation, false);
        }
        ListIterator<RewriteParams> it = unconditionalRewrites.listIterator(unconditionalRewrites.size());
        while (it.hasPrevious()) {
            RewriteParams rewrite = it.previous();
            Matcher matcher = Pattern
                    .compile(rewrite.regex.replace("/", "\\/"))
                    .matcher(revertedPath);
            if (!matcher.matches()) {
                LOGGER.debug("Unconditional rewrite wasn't matched: {}", rewrite);
            } else {
                LOGGER.debug("Unconditional rewrite was matched: {}", rewrite);
                StringBuffer sb = new StringBuffer();
                matcher.appendReplacement(sb, rewrite.replace).appendTail(sb);
                revertedPath = sb.toString();
                LOGGER.debug("Unconditionally reverted path: {}", revertedPath);
            }
        }
        LOGGER.info("Reverted path: {}", revertedPath);
        return revertedPath;
    }

    private String revertPath(String path, RewriteParams rewrite, NgxBlock location, boolean optional) {
        LOGGER.info("Revert path {} with {} in location {}", path, rewrite, location);
        Matcher matcher = Pattern
                .compile(rewrite.regex.replace("/", "\\/"))
                .matcher(path);
        if (!matcher.matches()) {
            if (optional) {
                LOGGER.debug("Rewrite wasn't matched");
                return path;
            }
            throw new IllegalStateException("Rewrite " + rewrite + " doesn't match path " + path);
        }
        StringBuffer sb = new StringBuffer();
        matcher.appendReplacement(sb, rewrite.replace).appendTail(sb);
        LOGGER.debug("Reverted path: {}", sb);
        return sb.toString();
    }

    private void stepIn(Iterator<NgxEntry> innerIterator) {
        steps.push(iterator);
        iterator = innerIterator;
    }

    private void stepOut() {
        if (locationIterator == iterator) {
            locationEnd();
        }
        iterator = steps.poll();
    }

    private void entry(NgxEntry entry) {
        try {
            if (entry instanceof NgxBlock) {
                block((NgxBlock) entry);
            } else if (entry instanceof NgxParam) {
                param((NgxParam) entry);
            }
        } catch (FinishProcessing e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to process entry: " + entry, e);
        }
    }

    private void block(NgxBlock block) {
        stepIn(block.iterator());

        if (block instanceof NgxConfig) {
            return;
        }

        String name = block.getTokens().iterator().next().getToken();
        if (block instanceof NgxIfBlock) {
            ifBlock((NgxIfBlock) block);
        } else if (name.equals(LOCATION)) {
            location(block);
        }
    }

    private void location(NgxBlock location) {
        if (locationIterator != null) {
            LOGGER.debug("Parent location: {}", this.location);
            LOGGER.debug("Nested location: {}", location);
        }
        this.location = location;
        locationType = LocationType.PREFIX;
        locationRegex = null;
        locationIterator = iterator;
        identifyLocation();
        if (locationType.regex && regexLocation != null) {
            LOGGER.debug("Skipping regex location");
            stepOut();
        }
    }

    private void locationEnd() {
        location = null;
        locationType = null;
        locationRegex = null;
        locationIterator = null;
    }

    private void identifyLocation() {
        Iterator<String> args = location.getValues().iterator();
        if (!args.hasNext()) {
            throw new IllegalStateException("Useless location");
        }
        String arg = args.next();
        for (LocationType type : LocationType.values()) {
            if (arg.equals(type.op)) {
                locationType = type;
                if (!args.hasNext()) {
                    throw new IllegalStateException("Useless location");
                }
                arg = args.next();
                break;
            }
        }
        StringBuilder url = new StringBuilder(arg);
        while (args.hasNext()) {
            url.append(args.next());
        }
        locationUrl = url.toString();
        if (locationType.regex) {
            normalizeRegex(url);
        } else if (locationType.prefix) {
            prefixToRegex(url);
        }
        locationRegex = url.toString().replace(ID_REGEX, ID_MARK);
        LOGGER.debug("Location URL: {}", locationUrl);
    }

    private static void prefixToRegex(StringBuilder url) {
        url.append("(.*)");
    }

    private static void normalizeRegex(StringBuilder url) {
        if (url.charAt(0) == '^') {
            url.deleteCharAt(0);
        }
        int lastIdx = url.length() - 1;
        if (url.charAt(lastIdx) == '$') {
            url.deleteCharAt(lastIdx);
        } else {
            prefixToRegex(url);
        }
    }

    private RewriteParams revertLocationRewrite(RewriteParams rewrite) {
        return revertRewrite(locationUrl, rewrite);
    }

    private static RewriteParams revertGlobalRewrite(RewriteParams rewrite) {
        return revertRewrite(null, rewrite);
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

    private void ifBlock(NgxIfBlock block) {
        Iterator<String> args = argsIterator(block.getValues());
        String var = args.next();
        String op = args.next();
        String arg = args.next();
        if (var == null) {
            throw new IllegalStateException("Useless if block");
        }

        if (var.equals(REQUEST_METHOD)) {
            if (op == null) {
                throw new IllegalStateException("Unspecified operation");
            }
            if (!op.equals("=")) {
                throw new IllegalStateException("Unsupported operation");
            }
            if (arg == null) {
                throw new IllegalStateException("Useless comparison");
            }
            if (!arg.equals(httpMethod)) {
                LOGGER.debug("Condition wasn't matched: {}", block);
                stepOut();
            } else {
                LOGGER.debug("Condition was matched: {}", block);
            }
        }
    }

    private void param(NgxParam param) {
        if (param.getName().equals(REWRITE)) {
            rewrite(param);
        }
    }

    private void rewrite(NgxParam rewrite) {
        try {
            RewriteParams params = revertLocationRewrite(new RewriteParams(rewrite));
            if (location == null) {
                LOGGER.debug("Store unconditional rewrite: {}", rewrite);
                unconditionalRewrites.add(params);
            } else {
                if (matchPath(params)) {
                    useMatchedLocation(params);
                    applyRewriteOption(params.opt);
                }
            }
        } catch (FinishProcessing e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to process rewrite with location: " + location, e);
        }
    }

    private boolean matchPath(RewriteParams rewrite) {
        Matcher matcher = Pattern
                .compile(rewrite.regex.replace("/", "\\/"))
                .matcher(markedPath);
        if (!matcher.matches()) {
            LOGGER.debug("Rewrite wasn't matched: {}", rewrite);
        } else {
            LOGGER.debug("Rewrite was matched: {}", rewrite);
            StringBuffer sb = new StringBuffer();
            matcher.appendReplacement(sb, rewrite.replace).appendTail(sb);
            String result = sb.toString();
            LOGGER.debug("Result URL: {}", result);
            matcher = Pattern
                    .compile(locationRegex.replace("/", "\\/"))
                    .matcher(result);
            if (!matcher.matches()) {
                LOGGER.debug("Path wasn't matched");
            } else {
                LOGGER.debug("Path was matched");
                return true;
            }
        }
        return false;
    }

    private void useMatchedLocation(RewriteParams rewrite) {
        if (locationType.prefix) {
            if (prefixLocation == null || prefixLocationUrl.length() < locationUrl.length()) {
                setPrefixLocation(rewrite);
            }
        } else if (locationType.regex) {
            setRegexLocation(rewrite);
        } else if (locationType == LocationType.STRICT) {
            setPrefixLocation(rewrite);
            resetRegexLocation();
            throw new FinishProcessing();
        } else {
            throw new IllegalStateException("Location match type is not supported");
        }
    }

    private void setPrefixLocation(RewriteParams rewrite) {
        prefixLocation = location;
        prefixLocationType = locationType;
        prefixLocationUrl = locationUrl;
        prefixRewrite = rewrite;
    }

    private void setRegexLocation(RewriteParams rewrite) {
        regexLocation = location;
        regexRewrite = rewrite;
    }

    private void resetRegexLocation() {
        regexLocation = null;
        regexRewrite = null;
    }

    private void applyRewriteOption(String opt) {
        if (opt != null) {
            if (opt.equals(BREAK)) {
                stepOut();
            } else if (opt.equals(LAST)) {
                throw new FinishProcessing();
            } else {
                throw new IllegalStateException("Unsupported rewrite option");
            }
        }
    }
}
