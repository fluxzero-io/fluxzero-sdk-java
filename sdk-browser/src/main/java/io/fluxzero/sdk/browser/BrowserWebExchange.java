/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.fluxzero.sdk.browser;

import java.util.Map;

/**
 * Browser-safe simulated web request/response exchange.
 */
public final class BrowserWebExchange {
    private final String method;
    private final String path;
    private final Object body;
    private final Map<String, String> pathParameters;
    private final Map<String, String> headers;
    private final Map<String, String> query;
    private final Map<String, String> cookies;
    private final Map<String, String> form;
    private final int status;
    private final Object responseBody;

    public BrowserWebExchange(String method, String path, Object body, Map<String, String> headers,
                              Map<String, String> query, Map<String, String> cookies, int status,
                              Object responseBody) {
        this(method, path, body, Map.of(), headers, query, cookies, Map.of(), status, responseBody);
    }

    public BrowserWebExchange(String method, String path, Object body, Map<String, String> pathParameters,
                              Map<String, String> headers, Map<String, String> query, Map<String, String> cookies,
                              Map<String, String> form, int status, Object responseBody) {
        this.method = method;
        this.path = path;
        this.body = body;
        this.pathParameters = Map.copyOf(pathParameters);
        this.headers = Map.copyOf(headers);
        this.query = Map.copyOf(query);
        this.cookies = Map.copyOf(cookies);
        this.form = Map.copyOf(form);
        this.status = status;
        this.responseBody = responseBody;
    }

    public String method() {
        return method;
    }

    public String path() {
        return path;
    }

    public Object body() {
        return body;
    }

    public Map<String, String> pathParameters() {
        return pathParameters;
    }

    public Map<String, String> headers() {
        return headers;
    }

    public Map<String, String> query() {
        return query;
    }

    public Map<String, String> cookies() {
        return cookies;
    }

    public Map<String, String> form() {
        return form;
    }

    public int status() {
        return status;
    }

    public Object responseBody() {
        return responseBody;
    }

    public BrowserWebExchange withResponse(int status, Object responseBody) {
        return new BrowserWebExchange(method, path, body, pathParameters, headers, query, cookies, form,
                                      status, responseBody);
    }

    BrowserWebExchange withPathParameters(Map<String, String> pathParameters) {
        return new BrowserWebExchange(method, path, body, pathParameters, headers, query, cookies, form,
                                      status, responseBody);
    }
}
