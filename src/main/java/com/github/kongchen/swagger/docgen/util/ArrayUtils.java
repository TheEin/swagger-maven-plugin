package com.github.kongchen.swagger.docgen.util;

/**
 * Utility functions for arrays
 */
public class ArrayUtils {

    private ArrayUtils() {
    }

    /**
     * Get the only single element of an array
     *
     * @param array input
     * @param <T>   element type
     * @return the single element
     * @throws NullPointerException     if the input array is null
     * @throws IllegalArgumentException if the input array length is not {@code 1}
     */
    public static <T> T getSingleElement(T[] array) {
        if (array == null) {
            throw new NullPointerException("array");
        }
        if (array.length != 1) {
            throw new IllegalArgumentException("Not a singleton array");
        }
        return array[0];
    }
}
