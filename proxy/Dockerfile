FROM gcr.io/distroless/java25-debian13:nonroot@sha256:92ade274abe7261c8891c97dc6c35ec79516e6ba3ea1fcac5a573d8075282d68

COPY target/fluxzero-proxy.jar .

CMD [ "-XX:MaxRAMPercentage=75.0", "-jar", "fluxzero-proxy.jar" ]