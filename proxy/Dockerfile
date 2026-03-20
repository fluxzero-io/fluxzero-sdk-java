FROM gcr.io/distroless/java25-debian13:nonroot@sha256:7c2ef3e630046d73ae8372add70a23cbf6f0c334c76c6a9196a21b01abb45559

COPY target/fluxzero-proxy.jar .

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["/usr/bin/java", "-jar", "fluxzero-proxy.jar"]
