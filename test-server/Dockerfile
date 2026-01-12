FROM gcr.io/distroless/java25-debian13:nonroot@sha256:70612f50341c5de9d3600493b18d074f2b0daad4f5b6003047fe9a5b4e3ece65

COPY target/fluxzero-test-server.jar .

CMD [ "-XX:MaxRAMPercentage=75.0", "-jar", "fluxzero-test-server.jar" ]
