/*
 * Copyright Fluxzero IP B.V. Licensed under the Apache License, Version 2.0.
 */
package io.fluxzero.sdk.web;

import com.fasterxml.jackson.databind.JsonNode;
import io.fluxzero.common.serialization.JsonUtils;
import java.util.Collections;

@ApiDocInfo(serveOpenApi = true)
public class PackagingProbe {
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        var sources = Collections.list(PackagingProbe.class.getClassLoader()
                .getResources("META-INF/fluxzero/openapi.json"));
        System.out.println("OPENAPI_SOURCES " + sources);
        if (sources.size() != Integer.parseInt(args[1])) {
            throw new AssertionError("Wrong packaged resource count: " + sources);
        }
        var endpoint = OpenApiDocumentEndpoint.forHandler(PackagingProbe.class, new PackagingProbe()).getFirst();
        if (!mode.equals("valid")) {
            try {
                endpoint.validateResources();
            } catch (IllegalArgumentException e) {
                String message = e.getMessage();
                String expected = mode.equals("conflict") ? "/info/title" : "operationId";
                if (!message.contains(expected) || !message.contains("#document=")) {
                    throw new AssertionError("Missing conflict/source context", e);
                }
                System.out.println("OPENAPI_OK " + mode);
                return;
            }
            throw new AssertionError("Conflicting packaged resources were accepted");
        }
        endpoint.validateResources();
        String body = endpoint.response().getPayloadAs(String.class);
        JsonNode document = JsonUtils.fromJson(body, JsonNode.class);
        if (!document.path("paths").has("/first") || !document.path("paths").has("/second")
                || !document.at("/info/title").asText().equals("Shared API")) {
            throw new AssertionError("Incomplete served contract: " + document);
        }
        System.out.println("OPENAPI_OK valid");
    }
}
