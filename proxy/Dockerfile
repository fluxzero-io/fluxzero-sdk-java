FROM gcr.io/distroless/java25-debian13:nonroot@sha256:fa9bfc14924fa3b43d43944d93887155d19843b3aa45610b659496f928fe2a9c

COPY target/fluxzero-proxy.jar .

CMD [ "-XX:MaxRAMPercentage=75.0", "-jar", "fluxzero-proxy.jar" ]