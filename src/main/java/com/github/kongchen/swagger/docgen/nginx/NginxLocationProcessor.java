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
            String s = "rewrite " + regex + " " + replace;
            if (opt != null && !opt.isEmpty()) {
                s += " " + opt;
            }
            return s;
        }
    }

    private static class FinishProcessing extends RuntimeException {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(NginxLocationProcessor.class);

    private static final Pattern ID_REGEX = Pattern.compile("\\\\d\\+|\\[\\^/]\\+");
    private static final Pattern PATH_ID = Pattern.compile("\\{\\w+}");
    private static final String ID_MARK = String.valueOf(RandomUtils.nextInt(1 << 30, Integer.MAX_VALUE));

    private final NgxConfig config;
    private final Operation operation;
    protected final String path;
    private final String markedPath;
    private final String httpMethod;
    protected final List<Pattern> notFoundLocations = new ArrayList<>();
    private final Deque<Iterator<NgxEntry>> steps = new LinkedList<>();

    private Iterator<NgxEntry> iterator;
    private NgxBlock location;
    private LocationType locationType;
    protected String locationUrl;
    protected Pattern locationRegex;
    private Iterator<NgxEntry> locationIterator;
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
        return path;
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
        locationRegex = Pattern.compile(
                ID_REGEX.matcher(url).replaceAll(ID_MARK)
                        .replace("/", "\\/"));
        LOGGER.debug("Location URL: {}", locationUrl);
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
        String name = param.getName();
        if (name.equals(REWRITE)) {
            rewrite(param);
        } else if (name.equals(RETURN)) {
            ret(param);
        }
    }

    private void rewrite(NgxParam rewrite) {
        try {
            RewriteParams params = identifyRewrite(new RewriteParams(rewrite));
            if (location == null) {
                LOGGER.debug("Unconditional rewrite: {}", rewrite);
                unconditionalRewrite(params);
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

    protected abstract RewriteParams identifyRewrite(RewriteParams rewrite);

    protected abstract void unconditionalRewrite(RewriteParams rewrite);

    private void ret(NgxParam ret) {
        int code = Integer.parseInt(ret.getValue());
        if (code == 404) {
            LOGGER.debug("Store location with 404 return: {}", locationUrl);
            notFoundLocations.add(locationRegex);
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
