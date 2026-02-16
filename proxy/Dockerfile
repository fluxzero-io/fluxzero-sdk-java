FROM gcr.io/distroless/java25-debian13:nonroot@sha256:e1eeec12952b877762d2a0a94a216600b34056942f6d82635a1d4a5dc8ee9770

COPY target/fluxzero-proxy.jar .

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["/usr/bin/java", "-jar", "fluxzero-proxy.jar"]
