#빌드스테이지
FROM eclipse-temurin:25-jdk AS build
WORKDIR  /app

COPY gradlew ./
#gradle설정 파일 안에 적힌 버전 확인(없으면 gradle설치)
COPY gradle ./gradle

COPY build.gradle settings.gradle ./
#세팅 파일 복사

COPY src ./src
 #프로젝트 코드 파일(scr) -> 내부 scr로 복사


RUN chmod +x ./gradlew && ./gradlew clean bootJar
#기존 결과물 삭제-> 새로운 jar생성


#런타임 스테이지
FROM eclipse-temurin:25-jre AS runtime

WORKDIR /app

COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
#컨테이너가 시작될떄 항상 실행됨.
#JVM실행 -> jar실행 모드에서 .jar파일실행