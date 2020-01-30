package com.nexign.swagger.annotations;

import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponses;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks {@link ApiOperation} having standard {@link ApiResponses} according to Nexign OAPI rules
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AutoApiResponses {
}
