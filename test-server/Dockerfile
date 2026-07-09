FROM gcr.io/distroless/java25-debian13:nonroot@sha256:4a1982383b6f66a32ee1e1c9dd7b0e171b44553c72da6045e874c70cc3c24413

COPY target/fluxzero-test-server.jar .

CMD [ "-XX:MaxRAMPercentage=75.0", "-jar", "fluxzero-test-server.jar" ]
