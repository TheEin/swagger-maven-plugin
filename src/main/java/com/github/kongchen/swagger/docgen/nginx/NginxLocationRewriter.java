package com.github.kongchen.swagger.docgen.nginx;

import com.github.odiszapc.nginxparser.NgxBlock;
import com.github.odiszapc.nginxparser.NgxConfig;
import com.github.odiszapc.nginxparser.NgxEntry;
import com.github.odiszapc.nginxparser.NgxIfBlock;
import com.github.odiszapc.nginxparser.NgxParam;

import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

public class NginxLocationRewriter {

    private static final String LOCATION = "location";

    private final NgxConfig config;

    private final String operationPath;

    private final String httpMethod;

    private final Deque<Iterator<NgxEntry>> steps = new LinkedList<>();

    private Iterator<NgxEntry> iterator;

    private String revertedPath;

    private NgxBlock location;

    private NgxBlock prefixLocation;

    private NgxBlock patternLocation;

    public NginxLocationRewriter(NgxConfig config, String operationPath, String httpMethod) {
        this.config = config;
        this.operationPath = operationPath;
        this.httpMethod = httpMethod;
    }

    public String revertPath() {
        revertedPath = operationPath;
        iterator = config.iterator();
        do {
            while (iterator.hasNext()) {
                NgxEntry entry = iterator.next();
                if (entry instanceof NgxBlock) {
                    block((NgxBlock) entry);
                } else if (entry instanceof NgxParam) {
                    param((NgxParam) entry);
                }
            }
            iterator = steps.poll();
        } while (iterator != null);
        return revertedPath;
    }

    private void block(NgxBlock block) {
        steps.push(iterator);
        iterator = block.iterator();

        if (block instanceof NgxConfig) {
            return;
        }

        String name = block.getTokens().iterator().next().getToken();
        if (block instanceof NgxIfBlock) {
            ifBlock(block.getValues());
        } else if (name.equals(LOCATION)) {
            location = block;
        }
    }

    private void ifBlock(List<String> values) {
    }

    private void param(NgxParam param) {
    }
}
