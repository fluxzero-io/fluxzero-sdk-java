FROM gcr.io/distroless/java25-debian13:nonroot@sha256:e1eeec12952b877762d2a0a94a216600b34056942f6d82635a1d4a5dc8ee9770

COPY target/fluxzero-proxy.jar .

CMD [ "-XX:MaxRAMPercentage=75.0", "-jar", "fluxzero-proxy.jar" ]