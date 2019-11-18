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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.github.kongchen.swagger.docgen.nginx.NginxDirective.BREAK;
import static com.github.kongchen.swagger.docgen.nginx.NginxDirective.LAST;
import static com.github.kongchen.swagger.docgen.nginx.NginxDirective.LOCATION;
import static com.github.kongchen.swagger.docgen.nginx.NginxDirective.REQUEST_METHOD;
import static com.github.kongchen.swagger.docgen.nginx.NginxDirective.RETURN;
import static com.github.kongchen.swagger.docgen.nginx.NginxDirective.REWRITE;

public abstract class NginxLocationProcessor {

    private static class Cursor {

        public Cursor(NgxBlock block) {
            this.block = block;
            this.iterator = block.iterator();
        }

        public final NgxBlock block;
        public final Iterator<NgxEntry> iterator;
    }

    private static class FinishProcessing extends RuntimeException {
    }

    protected enum LocationType {
        PREFIX(null, true, false, false),
        NO_REGEX("^~", true, false, true),
        STRICT("=", false, false, true),
        REGEX_MATCH("~", false, true, false),
        IREGEX_MATCH("~*", false, true, false);

        public final String op;

        public final boolean prefix;

        public final boolean regex;

        public final boolean noRegex;

        LocationType(String op, boolean prefix, boolean regex, boolean noRegex) {
            this.op = op;
            this.prefix = prefix;
            this.regex = regex;
            this.noRegex = noRegex;
        }
    }

    protected static class RewriteParams {

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
            String s = "Rewrite " + regex + " " + replace;
            if (opt != null && !opt.isEmpty()) {
                s += " " + opt;
            }
            return s;
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(NginxLocationProcessor.class);

    private static final Pattern PATH_ID = Pattern.compile("\\{\\w+}");

    protected static final String ID_MARK = String.valueOf(RandomUtils.nextInt(1 << 30, Integer.MAX_VALUE));

    private final NgxConfig config;
    private final Operation operation;
    private final String httpMethod;
    private final List<Pattern> notFoundLocations = new ArrayList<>();
    private final Deque<Cursor> breadcrumbs = new LinkedList<>();

    private Cursor cursor;
    private Cursor locationCursor;
    private NgxBlock location;
    private LocationType locationType;

    protected String path;
    protected String markedPath;
    protected String locationUrl;
    protected Pattern locationRegex;
    protected NgxBlock prefixLocation;
    protected LocationType prefixLocationType;
    protected String prefixLocationUrl;
    protected RewriteParams prefixRewrite;
    protected NgxBlock regexLocation;
    protected RewriteParams regexRewrite;

    protected static <T> Iterator<T> argsIterator(Iterable<T> source) {
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

    protected static String applyUnconditionalRewrite(RewriteParams rewrite, String path) {
        Matcher matcher = Pattern
                .compile(rewrite.regex.replace("/", "\\/"))
                .matcher(path);
        if (!matcher.matches()) {
            LOGGER.debug("Unconditional rewrite wasn't matched: {}", rewrite);
        } else {
            LOGGER.debug("Unconditional rewrite was matched: {}", rewrite);
            StringBuffer sb = new StringBuffer();
            matcher.appendReplacement(sb, rewrite.replace).appendTail(sb);
            path = sb.toString();
            LOGGER.debug("Unconditionally rewritten path: {}", path);
        }
        return path;
    }

    protected NginxLocationProcessor(NgxConfig config, String path, String httpMethod, Operation operation) {
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
    }

    public String process() {
        LOGGER.info("Processing {} {}, operationId = {}", httpMethod, path, operation.getOperationId());
        cursor = new Cursor(config);
        try {
            do {
                while (cursor.iterator.hasNext()) {
                    entry(cursor.iterator.next());
                }
                stepOut();
            } while (cursor != null);
        } catch (FinishProcessing ignore) {
            // use locations were found
        }
        return path;
    }

    protected Pattern searchNotFoundLocations(String path) {
        for (Pattern notFoundLocation : notFoundLocations) {
            if (notFoundLocation.matcher(path).matches()) {
                if (LOGGER.isInfoEnabled()) {
                    LOGGER.info("Use location {} to return 404 on path {}", notFoundLocation.pattern(), path);
                }
                return notFoundLocation;
            }
        }
        return null;
    }

    protected String applyLocationRewrites(String path) {
        if (regexLocation != null && (prefixLocation == null || !prefixLocationType.noRegex)) {
            LOGGER.info("Rewrite in location: {}", regexLocation);
            path = rewritePath(regexRewrite, path, false);
        } else if (prefixLocation != null) {
            LOGGER.info("Rewrite in location : {}", prefixLocation);
            path = rewritePath(prefixRewrite, path, false);
        }
        return path;
    }

    private String rewritePath(RewriteParams rewrite, String path, boolean optional) {
        LOGGER.info("{} on path {}", rewrite, path);
        Matcher matcher = Pattern
                .compile(rewrite.regex.replace("/", "\\/"))
                .matcher(path);
        if (!matcher.matches()) {
            if (optional) {
                LOGGER.debug("Rewrite wasn't matched");
                return path;
            }
            throw new IllegalStateException(rewrite + " doesn't match path " + path);
        }
        StringBuffer sb = new StringBuffer();
        matcher.appendReplacement(sb, rewrite.replace).appendTail(sb);
        LOGGER.debug("Rewritten path: {}", sb);
        return sb.toString();
    }

    private void stepIn(NgxBlock block) {
        breadcrumbs.push(cursor);
        cursor = new Cursor(block);
    }

    private void stepOut() {
        if (locationCursor == cursor) {
            locationEnd();
        }
        cursor = breadcrumbs.poll();
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
        stepIn(block);

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
        LOGGER.debug("Enter {}", location);
        if (locationCursor != null) {
            LOGGER.debug("Parent location: {}", locationCursor.block);
        }
        this.location = location;
        locationType = LocationType.PREFIX;
        locationRegex = null;
        locationCursor = cursor;
        String regex = identifyLocation();
        LOGGER.debug("Location regex: {}", regex);
        locationRegex = Pattern.compile(regex);
        if (locationType.regex && regexLocation != null) {
            LOGGER.debug("Skipping regex location");
            stepOut();
        }
    }

    private void locationEnd() {
        LOGGER.debug("Exit {}", location);
        location = null;
        locationType = null;
        locationRegex = null;
        locationCursor = null;
    }

    protected String identifyLocation() {
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
        StringBuilder regex = new StringBuilder(arg);
        while (args.hasNext()) {
            regex.append(args.next());
        }
        locationUrl = regex.toString();
        if (locationType.regex) {
            normalizeRegex(regex);
        } else if (locationType.prefix) {
            prefixToRegex(regex);
        }
        return regex.toString();
    }

    private static void prefixToRegex(StringBuilder url) {
        url.append("(.*)");
    }

    protected static void normalizeRegex(StringBuilder url) {
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

    private void ifBlock(NgxIfBlock ifBlock) {
        LOGGER.debug("Evaluate {}", ifBlock);
        Iterator<String> args = argsIterator(ifBlock.getValues());
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
                LOGGER.debug("Condition wasn't matched: {}", ifBlock);
                stepOut();
            } else {
                LOGGER.debug("Condition was matched: {}", ifBlock);
            }
        }
    }

    private void param(NgxParam param) {
        String name = param.getName();
        if (name.equals(REWRITE)) {
            rewrite(param);
        } else if (name.equals(RETURN)) {
            ret(param);
        }
    }

    private void rewrite(NgxParam rewrite) {
        LOGGER.debug("Found {}", rewrite);
        try {
            RewriteParams params = identifyRewrite(new RewriteParams(rewrite));
            if (location == null) {
                LOGGER.debug("Unconditional rewrite: {}", rewrite);
                unconditionalRewrite(params);
            } else {
                if (matchRewrite(params)) {
                    useMatchedRewrite(params);
                    applyRewriteOption(params.opt);
                }
            }
        } catch (FinishProcessing e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to process rewrite with location: " + location, e);
        }
    }

    protected abstract RewriteParams identifyRewrite(RewriteParams rewrite);

    protected abstract void unconditionalRewrite(RewriteParams rewrite);

    protected abstract boolean matchRewrite(RewriteParams rewrite);

    private void ret(NgxParam ret) {
        LOGGER.debug("Found ret {}", ret);
        int code = Integer.parseInt(ret.getValue());
        if (code == 404) {
            LOGGER.debug("Store location with 404 return: {}", locationUrl);
            notFoundLocations.add(locationRegex);
        }
    }

    private void useMatchedRewrite(RewriteParams rewrite) {
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
