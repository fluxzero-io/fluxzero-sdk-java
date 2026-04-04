FROM gcr.io/distroless/java25-debian13:nonroot@sha256:efb9a5000ce8ff56745d56c88c8e61017e674ed053b67e0c44af25ddabf1faa8

COPY target/fluxzero-proxy.jar .

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["/usr/bin/java", "-jar", "fluxzero-proxy.jar"]
