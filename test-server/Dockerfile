FROM gcr.io/distroless/java25-debian13:nonroot@sha256:9a9777e171398c77c894a4a2164eb155eb960f586d2e35299460298b2c8167a3

COPY target/fluxzero-test-server.jar .

CMD [ "-XX:MaxRAMPercentage=75.0", "-jar", "fluxzero-test-server.jar" ]
