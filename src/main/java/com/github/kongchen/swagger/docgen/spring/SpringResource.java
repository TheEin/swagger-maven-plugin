package com.github.kongchen.swagger.docgen.spring;

import com.github.kongchen.swagger.docgen.util.SpringUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.RequestMethod;

import java.lang.reflect.Method;

/**
 * @author tedleman
 */
public class SpringResource {
    private Class<?> controllerClass;
    private Method method;
    private RequestMethod requestMethod;
    private String resourceName;
    private String resourceKey;
    private String description;
    private String controllerMapping; //FIXME should be an array

    /**
     * @param controllerClass Controller class
     * @param resourceName    resource Name
     * @param resourceKey     key containing the controller package, class controller class name, and controller-level @RequestMapping#value
     * @param description     description of the contrroller
     */
    public SpringResource(Class<?> controllerClass, Method method, RequestMethod requestMethod, String resourceName, String resourceKey, String description) {
        this.controllerClass = controllerClass;
        this.method = method;
        this.requestMethod = requestMethod;
        this.resourceName = resourceName;
        this.resourceKey = resourceKey;
        this.description = description;

        String[] controllerRequestMappingValues = SpringUtils.getControllerResquestMapping(this.controllerClass);

        this.controllerMapping = StringUtils.removeEnd(controllerRequestMappingValues[0], "/");
    }

    public Class<?> getControllerClass() {
        return controllerClass;
    }

    public void setControllerClass(Class<?> controllerClass) {
        this.controllerClass = controllerClass;
    }

    public Method getMethod() {
        return method;
    }

    public void setMethod(Method method) {
        this.method = method;
    }

    public RequestMethod getRequestMethod() {
        return requestMethod;
    }

    public void setRequestMethod(RequestMethod requestMethod) {
        this.requestMethod = requestMethod;
    }

    public String getControllerMapping() {
        return controllerMapping;
    }

    public void setControllerMapping(String controllerMapping) {
        this.controllerMapping = controllerMapping;
    }

    public String getResourceName() {
        return resourceName;
    }

    public void setResource(String resource) {
        this.resourceName = resource;
    }

    public String getResourcePath() {
        return "/" + resourceName;
    }

    public String getResourceKey() {
        return resourceKey;
    }

    public void setResourceKey(String resourceKey) {
        this.resourceKey = resourceKey;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

}
