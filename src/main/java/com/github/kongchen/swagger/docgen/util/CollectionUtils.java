package com.github.kongchen.swagger.docgen.util;

import java.util.Collection;

/**
 * Utility functions for collections
 */
public class CollectionUtils {

    private CollectionUtils() {
    }

    public static boolean isNotEmpty(Collection<?> collection) {
        return !collection.isEmpty();
    }
}
