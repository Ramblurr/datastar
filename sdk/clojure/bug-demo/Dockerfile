FROM docker.io/clojure:temurin-21-tools-deps-bookworm-slim AS builder

WORKDIR /opt

COPY . .

RUN cd bug-demo && clj -Sdeps '{:mvn/local-repo "./.m2/repository"}' -T:build uber

FROM docker.io/library/eclipse-temurin:21 AS runtime
COPY --from=builder /opt/bug-demo/target/bug-0.0.1-standalone.jar /app.jar

EXPOSE 3000

ENTRYPOINT ["java", "-cp", "app.jar", "clojure.main", "-m", "bug", "3000"]
