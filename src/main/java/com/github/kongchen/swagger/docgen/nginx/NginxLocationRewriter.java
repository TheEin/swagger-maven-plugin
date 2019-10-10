package com.github.kongchen.swagger.docgen.nginx;

import com.github.odiszapc.nginxparser.NgxBlock;
import com.github.odiszapc.nginxparser.NgxConfig;
import com.github.odiszapc.nginxparser.NgxEntry;
import com.github.odiszapc.nginxparser.NgxIfBlock;
import com.github.odiszapc.nginxparser.NgxParam;
import io.swagger.models.Operation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.NoSuchElementException;

public class NginxLocationRewriter {

    private static final Logger LOGGER = LoggerFactory.getLogger(NginxLocationRewriter.class);

    private static final String LOCATION = "location";

    private static final String REQUEST_METHOD = "$request_method";

    private static final String EQUALS = "=";

    private final NgxConfig config;

    private final String operationPath;

    private final String httpMethod;

    private final Operation operation;

    private final Deque<Iterator<NgxEntry>> steps = new LinkedList<>();

    private Iterator<NgxEntry> iterator;

    private String revertedPath;

    private NgxBlock location;

    private Iterator<NgxEntry> locationIterator;

    private NgxBlock prefixLocation;

    private NgxBlock patternLocation;

    public NginxLocationRewriter(NgxConfig config, String operationPath, String httpMethod, Operation operation) {
        if (operationPath == null) {
            throw new NullPointerException("operationPath");
        }
        if (httpMethod == null) {
            throw new IllegalArgumentException("HTTP method can't be null");
        }
        this.config = config;
        this.operationPath = operationPath;
        this.httpMethod = httpMethod.toUpperCase();
        this.operation = operation;
    }

    public String revertPath() {
        LOGGER.info("Reverting {} {}, operationId = {}", httpMethod, operationPath, operation.getOperationId());
        revertedPath = operationPath;
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
            locationIterator = null;
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
            if (locationIterator != null) {
                LOGGER.debug("Parent location: {}", location);
                LOGGER.debug("Nested location: {}", block);
            }
            location = block;
            locationIterator = iterator;
        }
    }

    private void ifBlock(NgxIfBlock block) {
        String var = null;
        String op = null;
        String arg = null;
        try {
            Iterator<String> it = block.getValues().iterator();
            var = it.next();
            op = it.next();
            arg = it.next();
        } catch (NoSuchElementException ignore) {
        }
        if (var == null) {
            throw new IllegalStateException("Useless if block");
        }

        if (var.equals(REQUEST_METHOD)) {
            if (op == null || !op.equals(EQUALS)) {
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
    }
}
