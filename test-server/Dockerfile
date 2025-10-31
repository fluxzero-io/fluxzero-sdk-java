FROM azul/zulu-openjdk-alpine:25-jre-headless

RUN apk upgrade

COPY target/fluxzero-test-server.jar /

# Add user and group for non-root
RUN addgroup -S java \
 && adduser -D -S -s /sbin/nologin -G java java

USER java
COPY entrypoint.sh /
ENTRYPOINT [ "/entrypoint.sh" ]
