FROM gcr.io/distroless/java25-debian13:nonroot@sha256:dade01b669efd3bea3977f73cc196c56f1ee678a71ec8305f84ec15fd5a23c8d

COPY target/fluxzero-proxy.jar .

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["/usr/bin/java", "-jar", "fluxzero-proxy.jar"]
