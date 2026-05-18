FROM gcr.io/distroless/java25-debian13:nonroot@sha256:8fd23b8538af756c885851a1676b4e16559f92d394d8e308b2d5e7c7ebf9179d

COPY target/fluxzero-proxy.jar .

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["/usr/bin/java", "-jar", "fluxzero-proxy.jar"]
