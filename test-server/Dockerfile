FROM gcr.io/distroless/java25-debian13:nonroot@sha256:e689f0e2ef32caa54a12928edce92460f69653c68d0a2edff5691fb5116ae5c6

COPY target/fluxzero-test-server.jar .

CMD [ "-XX:MaxRAMPercentage=75.0", "-jar", "fluxzero-test-server.jar" ]
