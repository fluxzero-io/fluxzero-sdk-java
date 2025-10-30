FROM gcr.io/distroless/java25-debian13:nonroot@sha256:1bea63434771d1a97f5bb8c37e5dfa3b06d7cfa188a1d271825927a19a02efdd

COPY target/fluxzero-test-server.jar .

CMD [ "-XX:MaxRAMPercentage=75.0", "-jar", "fluxzero-test-server.jar" ]
