package com.github.kongchen.swagger.docgen.nginx;

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

    private static class RewriteMatch {
        public final String regex;
        public final String replace;

        public RewriteMatch(String regex, String replace) {
            this.regex = regex;
            this.replace = replace;
        }
    }

    private static class FinishProcessing extends RuntimeException {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(NginxLocationRewriter.class);

    private static final String ID_REGEX = "\\d+";
    private static final Pattern PATH_ID = Pattern.compile("\\{\\w+}");
    private static final String ID_MARK = String.valueOf(RandomUtils.nextInt(1 << 30, Integer.MAX_VALUE));

    private final NgxConfig config;

    private final Operation operation;

    private final String path;

    private final String markedPath;

    private final String httpMethod;

    private final Deque<Iterator<NgxEntry>> steps = new LinkedList<>();

    private final List<NgxParam> unconditionalRewrites = new ArrayList<>();

    private Iterator<NgxEntry> iterator;

    private NgxBlock location;

    private LocationType locationType;

    private String locationUrl;

    private Iterator<NgxEntry> locationIterator;

    private NgxBlock prefixLocation;

    private LocationType prefixLocationType;

    private String prefixLocationUrl;

    private RewriteMatch prefixRewrite;

    private NgxBlock regexLocation;

    private RewriteMatch regexRewrite;

    private static <T> Iterator<T> infiniteIterator(Iterable<T> source) {
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

    public NginxLocationRewriter(NgxConfig config, String path, String httpMethod, Operation operation) {
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

    public String revertPath() {
        LOGGER.info("Reverting {} {}, operationId = {}", httpMethod, path, operation.getOperationId());
        iterator = config.iterator();
        try {
            do {
                while (iterator.hasNext()) {
                    NgxEntry entry = iterator.next();
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
                stepOut();
            } while (iterator != null);
        } catch (FinishProcessing ignore) {
            // use locations were found
        }
        String revertedPath = path;
        if (prefixLocation != null && prefixLocationType.noRegex) {
            revertedPath = revertWith(prefixRewrite);
        } else if (regexLocation != null) {
            revertedPath = revertWith(regexRewrite);
        } else if (prefixLocation != null) {
            revertedPath = revertWith(prefixRewrite);
        }
        LOGGER.debug("Reverted path: {}", revertedPath);
        return revertedPath;
    }

    private String revertWith(RewriteMatch rewrite) {
        return path; // TODO revert it!
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
        locationUrl = null;
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
        locationUrl = null;
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
        if (locationType.prefix) {
            prefixToRegex(url);
        } else if (locationType.regex) {
            normalizeRegex(url);
        }
        locationUrl = url.toString().replace(ID_REGEX, ID_MARK);
        LOGGER.debug("Location URL: {}", locationUrl);
    }

    private static void prefixToRegex(StringBuilder url) {
        if (url.charAt(url.length() - 1) != '/') {
            url.append('/');
        }
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

/*
    private void expandLocationUrls() {
        List<String> done = new ArrayList<>();
        while (!locationUrls.isEmpty()) {
            String url = locationUrls.remove(locationUrls.size() - 1);
            int lb = url.indexOf(LB);
            if (lb < 0) {
                done.add(url);
                continue;
            }
            int rb;
            List<Integer> vb = new ArrayList<>();
            int deep = 0;
            for (rb = lb + 1; rb < url.length(); ++rb) {
                int c = url.charAt(rb);
                if (c == RB) {
                    --deep;
                    if (deep < 0) {
                        break;
                    }
                } else if (c == LB) {
                    ++deep;
                } else if (c == VB) {
                    if (deep == 0) {
                        vb.add(rb);
                    }
                }
            }
            if (rb == url.length()) {
                throw new IllegalStateException("Unfinished group");
            }
            if (vb.isEmpty()) {
                throw new IllegalStateException("Useless group");
            }
        }
        locationUrls = done;
    }
*/

    private void ifBlock(NgxIfBlock block) {
        Iterator<String> args = infiniteIterator(block.getValues());
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
            if (location == null) {
                LOGGER.debug("Store unconditional rewrite: {}", rewrite);
                unconditionalRewrites.add(rewrite);
            } else {
                Iterator<String> args = infiniteIterator(rewrite.getValues());
                String regex = args.next();
                String replace = args.next();
                String opt = args.next();
                if (regex == null || replace == null) {
                    throw new IllegalStateException("Useless rewrite");
                }
                if (matchPath(rewrite, regex, replace)) {
                    useMatchedLocation(new RewriteMatch(regex, replace));
                    applyRewriteOption(opt);
                }
            }
        } catch (FinishProcessing e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to process rewrite with location: " + location, e);
        }
    }

    private boolean matchPath(NgxParam rewrite, String regex, String replace) {
        Matcher matcher = Pattern.compile(regex.replace("/", "\\/")).matcher(locationUrl);
        if (!matcher.matches()) {
            LOGGER.debug("Rewrite wasn't matched: {}", rewrite);
        } else {
            LOGGER.debug("Rewrite was matched: {}", rewrite);
            StringBuffer sb = new StringBuffer();
            matcher.appendReplacement(sb, replace).appendTail(sb);
            String result = sb.toString();
            LOGGER.debug("Result URL: {}", result);
            matcher = Pattern.compile(result).matcher(markedPath);
            if (!matcher.matches()) {
                LOGGER.debug("Path wasn't matched");
            } else {
                LOGGER.debug("Path was matched");
                return true;
            }
        }
        return false;
    }

    private void useMatchedLocation(RewriteMatch rewrite) {
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

    private void setPrefixLocation(RewriteMatch rewrite) {
        prefixLocation = location;
        prefixLocationType = locationType;
        prefixLocationUrl = locationUrl;
        prefixRewrite = rewrite;
    }

    private void setRegexLocation(RewriteMatch rewrite) {
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
