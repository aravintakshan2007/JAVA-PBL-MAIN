FROM eclipse-temurin:17-jdk

WORKDIR /app

COPY Cashpoint.java .
COPY sqlite-jdbc.jar .
COPY slf4j-api.jar .
COPY slf4j-nop.jar .

RUN javac -cp "sqlite-jdbc.jar:slf4j-api.jar:slf4j-nop.jar" Cashpoint.java

EXPOSE 10000

CMD ["sh", "-c", "java -cp '.:sqlite-jdbc.jar:slf4j-api.jar:slf4j-nop.jar' Cashpoint"]
