FROM maven:3-jdk-11 as build
ENV LANGUAGETOOL_VERSION=4.9

MAINTAINER Bruno Perel <brunoperel@gmail.com>

WORKDIR /srv
COPY . languagetool
RUN cd languagetool && mvn --projects languagetool-standalone --also-make package -DskipTests --quiet

WORKDIR /srv/languagetool-runtime
RUN unzip /srv/languagetool/languagetool-standalone/target/LanguageTool-$LANGUAGETOOL_VERSION.zip

FROM openjdk:11-jre-buster as languagetool-server
COPY --from=build /srv/languagetool-runtime /srv/languagetool-runtime

COPY ./entrypoint-languagetool-server.sh /home/entrypoint.sh
RUN chmod +x /home/entrypoint.sh
ENTRYPOINT ["sh", "-c", "/home/entrypoint.sh"]

EXPOSE 8010
