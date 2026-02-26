FROM gcr.io/distroless/java25-debian13:nonroot@sha256:4eadd00d3bff73e6a7491dd36653c1d318ac93fb1fb2cd5eef768fd2b4238408

COPY target/fluxzero-test-server.jar .

CMD [ "-XX:MaxRAMPercentage=75.0", "-jar", "fluxzero-test-server.jar" ]
