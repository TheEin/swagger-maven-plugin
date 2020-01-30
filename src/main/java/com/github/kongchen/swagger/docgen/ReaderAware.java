package com.github.kongchen.swagger.docgen;

import com.github.kongchen.swagger.docgen.reader.AbstractReader;

public interface ReaderAware {

    void setReader(AbstractReader reader);
}
