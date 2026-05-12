FROM gcr.io/distroless/java25-debian13:nonroot@sha256:c0d379ff54ea6d61f3f35736e8fdc66c91fb96f645a86a6ba2530a45d95b8841

COPY target/fluxzero-proxy.jar .

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["/usr/bin/java", "-jar", "fluxzero-proxy.jar"]
