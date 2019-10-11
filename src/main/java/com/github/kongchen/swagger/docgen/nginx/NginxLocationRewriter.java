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
import java.util.NoSuchElementException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NginxLocationRewriter {

    private enum Match {
        PREFIX(null, true, false),
        NO_REGEX("^~", true, false),
        STRICT("=", false, false),
        REGEX_MATCH("~", false, true),
        IREGEX_MATCH("~*", false, true);

        private final String op;

        private final boolean prefix;

        private final boolean regex;

        Match(String op, boolean prefix, boolean regex) {
            this.op = op;
            this.prefix = prefix;
            this.regex = regex;
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(NginxLocationRewriter.class);

    private static final String LOCATION = "location";

    private static final String REWRITE = "rewrite";

    private static final String REQUEST_METHOD = "$request_method";

    private static final String ID_REGEX = "\\d+";
    private static final Pattern PATH_ID = Pattern.compile("\\{\\w+}");
    private static final String ID_MARK = String.valueOf(RandomUtils.nextInt(1 << 30, Integer.MAX_VALUE));

    private final NgxConfig config;

    private final String path;

    private final String markedPath;

    private final String httpMethod;

    private final Operation operation;

    private final Deque<Iterator<NgxEntry>> steps = new LinkedList<>();

    private final List<NgxParam> unconditionalRewrites = new ArrayList<>();

    private Iterator<NgxEntry> iterator;

    private String revertedPath;

    private NgxBlock location;

    private Match locationMatch;

    private String locationUrl;

    private Iterator<NgxEntry> locationIterator;

    private NgxBlock prefixLocation;

    private NgxBlock patternLocation;

    public NginxLocationRewriter(NgxConfig config, String path, String httpMethod, Operation operation) {
        if (path == null) {
            throw new NullPointerException("operationPath");
        }
        if (httpMethod == null) {
            throw new IllegalArgumentException("HTTP method can't be null");
        }
        this.config = config;
        this.path = path;
        markedPath = PATH_ID.matcher(path).replaceAll(ID_MARK);
        this.httpMethod = httpMethod.toUpperCase();
        this.operation = operation;
    }

    public String revertPath() {
        LOGGER.info("Reverting {} {}, operationId = {}", httpMethod, path, operation.getOperationId());
        revertedPath = path;
        iterator = config.iterator();
        do {
            while (iterator.hasNext()) {
                NgxEntry entry = iterator.next();
                try {
                    if (entry instanceof NgxBlock) {
                        block((NgxBlock) entry);
                    } else if (entry instanceof NgxParam) {
                        param((NgxParam) entry);
                    }
                } catch (Exception e) {
                    throw new RuntimeException("Failed to process entry: " + entry, e);
                }
            }
            stepOut();
        } while (iterator != null);
        return revertedPath;
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
        locationMatch = Match.PREFIX;
        locationUrl = null;
        locationIterator = iterator;
        identifyLocation();
    }

    private void locationEnd() {
        location = null;
        locationMatch = null;
        locationUrl = null;
        locationIterator = null;
    }

    private void identifyLocation() {
        Iterator<String> args = location.getValues().iterator();
        if (!args.hasNext()) {
            throw new IllegalStateException("Useless location");
        }
        String arg = args.next();
        for (Match match : Match.values()) {
            if (arg.equals(match.op)) {
                locationMatch = match;
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
        if (locationMatch.prefix) {
            finishUrl(url);
        } else if (locationMatch.regex) {
            if (url.charAt(0) == '^') {
                url.deleteCharAt(0);
            }
            int lastIdx = url.length() - 1;
            if (url.charAt(lastIdx) == '$') {
                url.deleteCharAt(lastIdx);
            } else {
                finishUrl(url);
            }
        }
        locationUrl = url.toString().replace(ID_REGEX, ID_MARK);
    }

    private static void finishUrl(StringBuilder url) {
        if (url.charAt(url.length() - 1) != '/') {
            url.append('/');
        }
        url.append("(.*)");
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
        String var = null;
        String op = null;
        String arg = null;
        try {
            Iterator<String> args = block.getValues().iterator();
            var = args.next();
            op = args.next();
            arg = args.next();
        } catch (NoSuchElementException ignore) {
            // partial args
        }
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
            }
            LOGGER.debug("Condition was matched: {}", block);
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
                String regex = null;
                String replace = null;
                String opt = null;
                try {
                    Iterator<String> args = rewrite.getValues().iterator();
                    regex = args.next();
                    replace = args.next();
                    opt = args.next();
                } catch (NoSuchElementException ignore) {
                    // partial args
                }
                if (regex == null || replace == null) {
                    throw new IllegalStateException("Useless rewrite");
                }
                Matcher matcher = Pattern.compile(regex.replace("/", "\\/")).matcher(locationUrl);
                if (!matcher.matches()) {
                    throw new IllegalStateException("Rewrite wasn't matched");
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to process rewrite with location: " + locationUrl, e);
        }
    }
}
