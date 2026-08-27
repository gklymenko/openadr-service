FROM eclipse-temurin:21-jre-jammy

ARG APP_UID=10001
ARG JAR_FILE=target/openadr-service-*.jar

RUN groupadd --gid "${APP_UID}" openadr \
    && useradd --uid "${APP_UID}" --gid "${APP_UID}" --no-create-home --shell /usr/sbin/nologin openadr \
    && mkdir -p /srv/logs \
    && chown -R "${APP_UID}:${APP_UID}" /srv

WORKDIR /srv

COPY --chown=${APP_UID}:${APP_UID} ${JAR_FILE} /srv/openadr-service.jar

USER ${APP_UID}:${APP_UID}

EXPOSE 8081
VOLUME ["/srv/logs"]

ENTRYPOINT ["java", "-jar", "/srv/openadr-service.jar"]
