FROM gcr.io/distroless/java25-debian13:nonroot@sha256:b1eb8a18891104b7405f29edbb2eaca9b34179707957a0e5a41b54d4a45cbdfd

COPY target/fluxzero-proxy.jar .

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["/usr/bin/java", "-jar", "fluxzero-proxy.jar"]
