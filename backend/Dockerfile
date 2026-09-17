FROM maven:3.9-eclipse-temurin-11 AS build
WORKDIR /build

# Dependencies are resolved from the POM alone so this layer survives source changes.
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests

FROM eclipse-temurin:11-jre-alpine
# Both upload directories have to exist in the image, even empty. Compose mounts a named
# volume on each, and a volume mounted over a path that does not exist in the image is created
# by Docker as root — the application runs as `securityhub`, and the first upload would die
# with AccessDenied. Creating them here is what makes the volume inherit the right owner.
RUN apk add --no-cache wget \
 && addgroup -S securityhub \
 && adduser -S -G securityhub securityhub \
 && mkdir -p /var/securityhub/uploads /var/securityhub/uploads-scan \
 && chown -R securityhub:securityhub /var/securityhub

WORKDIR /app
COPY --from=build /build/target/securityhub-backend-*.jar app.jar
RUN chown securityhub:securityhub app.jar

USER securityhub
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
