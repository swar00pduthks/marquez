FROM gradle:8.7-jdk17 AS build
WORKDIR /usr/src/app
COPY . .
RUN gradle clean :api:shadowJar --no-daemon

FROM eclipse-temurin:17
RUN apt-get update && apt-get install -y postgresql-client bash coreutils
WORKDIR /usr/src/app
COPY --from=build /usr/src/app/api/build/libs/marquez-*.jar /usr/src/app
COPY marquez.dev.yml marquez.dev.yml
COPY docker/entrypoint.sh entrypoint.sh
RUN chmod +x entrypoint.sh
EXPOSE 5000 5001
ENTRYPOINT ["/usr/src/app/entrypoint.sh"]
