FROM gcr.io/distroless/java25-debian13:nonroot@sha256:4eadd00d3bff73e6a7491dd36653c1d318ac93fb1fb2cd5eef768fd2b4238408

COPY target/fluxzero-proxy.jar .

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["/usr/bin/java", "-jar", "fluxzero-proxy.jar"]
