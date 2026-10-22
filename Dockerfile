# syntax=docker/dockerfile:1
#
# Образ приложения (как в store). Два этапа: в первом JDK и Maven собирают jar, во второй
# попадает только JRE и результат — без компилятора, Maven и исходников.
# Тесты здесь не запускаются: их гоняет CI, а внутри сборки образа нет Docker для Testcontainers.

FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
# Сначала только pom.xml: пока он не менялся, слой с зависимостями берётся из кэша
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline
COPY src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests package \
    && java -Djarmode=tools -jar target/dental-*.jar extract --layers --launcher --destination /extracted

FROM eclipse-temurin:25-jre
WORKDIR /app
# Не root: если в приложении найдут дыру, у взломщика не будет прав root в контейнере
RUN groupadd --system dental && useradd --system --gid dental --no-create-home dental
# Слои от редко меняющихся к часто меняющимся: правка нашего кода меняет только последний
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
USER dental
EXPOSE 8080
# Куча — 75% от лимита памяти контейнера; при нехватке памяти процесс завершается,
# и Docker его перезапускает. UTF-8 — чтобы кириллица из переменных окружения не стала «????»
ENV LANG=C.UTF-8
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
