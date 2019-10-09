package com.github.kongchen.swagger.docgen.nginx;

import com.github.odiszapc.nginxparser.NgxBlock;
import com.github.odiszapc.nginxparser.NgxEntry;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Objects;

public class NginxBlockIterator<T extends NgxBlock> implements Iterator<NgxEntry>, AutoCloseable {

    private final T block;
    private final Iterator<NgxEntry> iterator;
    private int index;
    private ArrayList<NgxEntry> replacement;

    public NginxBlockIterator(T block) {
        this.block = block;
        iterator = block.iterator();
        index = -1;
    }

    @Override
    public boolean hasNext() {
        return iterator.hasNext();
    }

    @Override
    public NgxEntry next() {
        NgxEntry next = iterator.next();
        ++index;
        return next;
    }

    private ArrayList<NgxEntry> replacement() {
        if (replacement == null) {
            replacement = new ArrayList<>(block.getEntries());
        }
        return replacement;
    }

    @Override
    public void remove() {
        replacement().set(index, null);
    }

    public void replace(NgxEntry e) {
        replacement().set(index, e);
    }

    @Override
    public void close() {
        if (replacement != null) {
            block.getEntries().clear();
            replacement.stream()
                    .filter(Objects::nonNull)
                    .forEachOrdered(block::addEntry);
            replacement = null;
        }
    }
}
