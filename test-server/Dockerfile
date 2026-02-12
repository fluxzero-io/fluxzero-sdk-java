FROM gcr.io/distroless/java25-debian13:nonroot@sha256:29a8dfd3f2357a0b32839c2728893f5bcdacdde00eafa45c5c7b95e6f264b2b1

COPY target/fluxzero-test-server.jar .

CMD [ "-XX:MaxRAMPercentage=75.0", "-jar", "fluxzero-test-server.jar" ]
