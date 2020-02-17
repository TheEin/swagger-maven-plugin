package com.github.kongchen.swagger.docgen.util;

import io.swagger.models.Operation;
import io.swagger.models.Path;

/**
 * Utility functions for Swagger
 */
public class SwaggerUtils {

    private SwaggerUtils() {
    }

    public static void setPathOperation(Path path, String method, Operation op) {
        Operation oldOp;
        switch (method) {
            case "get":
                oldOp = path.getGet();
                path.get(op);
                break;
            case "put":
                oldOp = path.getPut();
                path.put(op);
                break;
            case "head":
                oldOp = path.getHead();
                path.head(op);
                break;
            case "post":
                oldOp = path.getPost();
                path.post(op);
                break;
            case "delete":
                oldOp = path.getDelete();
                path.delete(op);
                break;
            case "patch":
                oldOp = path.getPatch();
                path.patch(op);
                break;
            case "options":
                oldOp = path.getOptions();
                path.options(op);
                break;
            default:
                throw new IllegalArgumentException("Unknown HTTP method: " + method);
        }
        if (oldOp != null) {
            throw new IllegalStateException("Path method " + method.toUpperCase() + " was already set");
        }
    }
}
