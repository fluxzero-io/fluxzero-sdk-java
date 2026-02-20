FROM gcr.io/distroless/java25-debian13:nonroot@sha256:2e3d8fc12381605a522cb015f551cb340fd857afb51c71d7decf4fea0d8802d5

COPY target/fluxzero-test-server.jar .

CMD [ "-XX:MaxRAMPercentage=75.0", "-jar", "fluxzero-test-server.jar" ]
