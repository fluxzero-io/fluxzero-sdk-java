FROM gcr.io/distroless/java25-debian13:nonroot@sha256:85df69eb02f91fc64a7b5635a26e6d08323a9fa1adc35eb1c2ffba79960e8c87

COPY target/fluxzero-proxy.jar .

CMD [ "-XX:MaxRAMPercentage=75.0", "-jar", "fluxzero-proxy.jar" ]