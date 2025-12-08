FROM gcr.io/distroless/java25-debian13:nonroot@sha256:b898108eb09113c21e982d23d94345ccd91b756beb4399a590f74733598c5a0f

COPY target/fluxzero-proxy.jar .

CMD [ "-XX:MaxRAMPercentage=75.0", "-jar", "fluxzero-proxy.jar" ]