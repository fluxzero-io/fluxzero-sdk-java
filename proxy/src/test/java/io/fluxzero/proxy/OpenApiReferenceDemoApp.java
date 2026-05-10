/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.proxy;

import io.fluxzero.common.Registration;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import io.fluxzero.sdk.web.ApiDoc;
import io.fluxzero.sdk.web.ApiDocInfo;
import io.fluxzero.sdk.web.ApiDocResponse;
import io.fluxzero.sdk.web.ApiReferenceRenderer;
import io.fluxzero.sdk.web.HandleGet;
import io.fluxzero.sdk.web.HandlePost;
import io.fluxzero.sdk.web.Path;
import io.fluxzero.sdk.web.PathParam;
import io.fluxzero.sdk.web.QueryParam;
import io.fluxzero.sdk.web.WebResponse;
import io.fluxzero.testserver.TestServer;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static io.fluxzero.sdk.configuration.ApplicationProperties.containsProperty;
import static io.fluxzero.sdk.configuration.ApplicationProperties.getIntegerProperty;
import static io.fluxzero.sdk.configuration.ApplicationProperties.getProperty;
import static java.lang.String.format;

/**
 * Test-scope runnable that starts a Fluxzero test server, a proxy, and a small documented Fluxzero web application.
 */
@Slf4j
public class OpenApiReferenceDemoApp {
    private static final ConcurrentMap<String, Project> projects = new ConcurrentHashMap<>();

    public static void main(String[] args) throws InterruptedException {
        int fluxPort = getIntegerProperty("FLUX_PORT", 8888);
        if (availablePort(fluxPort)) {
            TestServer.start(fluxPort);
        } else {
            log.info("Fluxzero test server port {} is already in use; assuming a test server is already running",
                     fluxPort);
        }

        String runtimeBaseUrl = "ws://localhost:" + fluxPort;
        System.setProperty("FLUX_BASE_URL", runtimeBaseUrl);
        System.setProperty("FLUXZERO_BASE_URL", runtimeBaseUrl);

        WebSocketClient.ClientConfig appConfig = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl(runtimeBaseUrl)
                .name(getProperty("FLUX_APPLICATION_NAME", "OpenAPI Reference Demo"))
                .namespace(getProperty("FLUX_PROJECT_ID", "openapi-reference-demo"))
                .disableMetrics(true)
                .build();

        Client proxyClient = WebSocketClient.newInstance(appConfig.toBuilder()
                                                                 .id("proxy-" + UUID.randomUUID())
                                                                 .name("$proxy-openapi-reference-demo")
                                                                 .build());
        ProxyServer proxy = ProxyServer.start(proxyPort(), new ProxyRequestHandler(proxyClient));

        Fluxzero app = DefaultFluxzero.builder()
                .disableShutdownHook()
                .makeApplicationInstance(true)
                .build(WebSocketClient.newInstance(appConfig));
        Registration handlers = app.registerHandlers(
                new DemoEndpoint(), new ScalarDocsEndpoint(), new SwaggerUiDocsEndpoint());

        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("openapi-reference-demo-shutdown").unstarted(
                () -> {
                    handlers.cancel();
                    app.close(true);
                    proxy.cancel();
                }));

        log.info("OpenAPI reference demo is running");
        log.info("Runtime: {}", runtimeBaseUrl);
        log.info("Proxy:   http://localhost:{}", proxy.getPort());
        log.info("Redoc:   http://localhost:{}/demo/docs", proxy.getPort());
        log.info("Scalar:  http://localhost:{}/scalar-demo/docs", proxy.getPort());
        log.info("Swagger: http://localhost:{}/swagger-ui", proxy.getPort());
        log.info("Spec:    http://localhost:{}/demo/openapi.json", proxy.getPort());

        Thread.currentThread().join();
    }

    private static int proxyPort() {
        int configuredPort = getIntegerProperty("PROXY_PORT", 8080);
        if (containsProperty("PROXY_PORT") || availablePort(configuredPort)) {
            return configuredPort;
        }
        log.info("Proxy port {} is already in use; using a random available port", configuredPort);
        return 0;
    }

    static boolean availablePort(int port) {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            serverSocket.setReuseAddress(true);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Path("/demo")
    @ApiDocInfo(
            title = "Fluxzero OpenAPI Reference Demo",
            version = "1.0.0",
            description = """
                    Small test-scope application that demonstrates generated Fluxzero API documentation through \
                    the proxy and test-server.
                    """,
            serveApiReference = true)
    @ApiDoc(description = "Project and deployment endpoints.", tags = "Projects")
    static class DemoEndpoint {
        @HandleGet("/projects")
        @ApiDoc(summary = "List projects", description = "Returns a small in-memory project catalog.")
        List<Project> projects() {
            projects.putIfAbsent("fluxzero-sdk-java", sampleProject());
            return projects.values().stream().toList();
        }

        @HandleGet("/projects/{projectId}")
        @ApiDoc(summary = "Get project", description = "Returns one project by id.")
        @ApiDocResponse(status = 404, description = "Project not found")
        WebResponse project(
                @PathParam @ApiDoc(description = "Project identifier.", example = "fluxzero-sdk-java")
                String projectId,
                @QueryParam @ApiDoc(description = "Whether deployment history should be included.",
                        defaultValue = "true")
                Boolean includeDeployments) {
            Project project = projects.getOrDefault(projectId, sampleProject());
            if (!project.id().equals(projectId)) {
                return WebResponse.notFound("Project not found: " + projectId);
            }
            if (Boolean.FALSE.equals(includeDeployments)) {
                project = new Project(project.id(), project.name(), project.description(), List.of());
            }
            return WebResponse.builder().status(200).payload(project).build();
        }

        @HandlePost("/projects/{projectId}/deployments")
        @ApiDoc(summary = "Create deployment", description = "Adds a deployment record to a project.")
        @ApiDocResponse(status = 201, description = "Deployment created")
        WebResponse createDeployment(
                @PathParam @ApiDoc(description = "Project identifier.", example = "fluxzero-sdk-java")
                String projectId,
                @ApiDoc(description = "Deployment request payload.")
                DeploymentRequest request) {
            Project project = projects.getOrDefault(projectId, sampleProject());
            Deployment deployment = new Deployment(
                    "dep-" + UUID.randomUUID(), request.environment(), request.version(), request.dryRun(),
                    Instant.now());
            projects.put(project.id(), new Project(
                    project.id(), project.name(), project.description(),
                    java.util.stream.Stream.concat(project.deployments().stream(), java.util.stream.Stream.of(deployment))
                            .toList()));
            return WebResponse.builder().status(201).payload(deployment).build();
        }
    }

    @Path("/scalar-demo")
    @ApiDocInfo(
            title = "Fluxzero Scalar Reference Demo",
            version = "1.0.0",
            serveApiReference = true,
            apiReferenceRenderer = ApiReferenceRenderer.SCALAR)
    @ApiDoc(tags = "Scalar")
    static class ScalarDocsEndpoint {
        @HandleGet("/status")
        @ApiDoc(summary = "Get demo status")
        DemoStatus status() {
            return new DemoStatus("ok", Instant.now());
        }
    }

    @Path("/swagger-demo")
    @ApiDocInfo(
            title = "Fluxzero Swagger UI Reference Demo",
            version = "1.0.0",
            serveApiReference = true,
            apiReferenceRenderer = ApiReferenceRenderer.SWAGGER_UI,
            apiReferencePath = "/swagger-ui")
    @ApiDoc(tags = "Swagger UI")
    static class SwaggerUiDocsEndpoint {
        @HandleGet("/status")
        @ApiDoc(summary = "Get Swagger UI demo status")
        DemoStatus status() {
            return new DemoStatus("ok", Instant.now());
        }
    }

    private static Project sampleProject() {
        return new Project(
                "fluxzero-sdk-java",
                "Fluxzero Java SDK",
                "Java SDK used by Fluxzero applications.",
                List.of(new Deployment("dep-demo", "test", "1.0.0", true, Instant.parse("2026-05-10T00:00:00Z"))));
    }

    record Project(
            @ApiDoc(description = "Stable project identifier.", example = "fluxzero-sdk-java")
            String id,
            @ApiDoc(description = "Human-readable project name.", example = "Fluxzero Java SDK")
            String name,
            @ApiDoc(description = "Short project description.")
            String description,
            @ApiDoc(description = "Deployment history for this project.")
            List<@ApiDoc(description = "One deployment record.") Deployment> deployments) {
    }

    record Deployment(
            @ApiDoc(description = "Unique deployment identifier.", example = "dep-demo")
            String id,
            @ApiDoc(description = "Target environment.", allowableValues = {"test", "acceptance", "production"})
            String environment,
            @ApiDoc(description = "Application version that was deployed.", example = "1.0.0")
            String version,
            @ApiDoc(description = "Whether this deployment was a dry run.", defaultValue = "false")
            boolean dryRun,
            @ApiDoc(description = "Time at which the deployment was accepted.")
            Instant acceptedAt) {
    }

    record DeploymentRequest(
            @NotBlank
            @Pattern(regexp = "test|acceptance|production")
            @ApiDoc(description = "Target environment.", allowableValues = {"test", "acceptance", "production"},
                    example = "test")
            String environment,
            @NotBlank
            @ApiDoc(description = "Application version to deploy.", example = "1.0.1")
            String version,
            @PositiveOrZero
            @ApiDoc(description = "Requested deployment sequence number.", example = "42")
            int sequence,
            @ApiDoc(description = "Validate the deployment without applying it.", defaultValue = "false")
            boolean dryRun) {
    }

    record DemoStatus(
            @ApiDoc(description = "Current demo status.")
            String status,
            @ApiDoc(description = "Time at which the status was generated.")
            Instant timestamp) {
    }
}
