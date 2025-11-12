FROM gcr.io/distroless/java25-debian13:nonroot@sha256:66c715284387dc83cda507aa8def8e90ab7e1436553d4d4931adb1348cba2db2

COPY target/fluxzero-proxy.jar .

CMD [ "-XX:MaxRAMPercentage=75.0", "-jar", "fluxzero-proxy.jar" ]