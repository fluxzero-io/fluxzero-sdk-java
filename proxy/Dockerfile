FROM gcr.io/distroless/java25-debian13:nonroot@sha256:66a23001a1a4a5d02098c59fa27d265a8bb8e7d77d64464798e200441e53d040

COPY target/fluxzero-proxy.jar .

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["/usr/bin/java", "-jar", "fluxzero-proxy.jar"]
