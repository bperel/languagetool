FROM maven:3-jdk-11 as build
ENV LANGUAGETOOL_VERSION=4.9

MAINTAINER Bruno Perel <brunoperel@gmail.com>

WORKDIR /srv
RUN git clone --depth=1 --single-branch https://github.com/bperel/languagetool
RUN cd languagetool && mvn install -DskipTests && ./build.sh languagetool-server package -DskipTests

WORKDIR /srv/languagetool-runtime
RUN unzip /srv/languagetool/languagetool-standalone/target/LanguageTool-$LANGUAGETOOL_VERSION.zip \
 && rm -rf /srv/languagetool


FROM openjdk:11-jre-buster as languagetool-server
COPY --from=build /srv/languagetool-runtime /srv/languagetool-runtime

COPY ./entrypoint-languagetool-server.sh /home/entrypoint.sh
RUN chmod +x /home/entrypoint.sh
ENTRYPOINT ["sh", "-c", "/home/entrypoint.sh"]

EXPOSE 8010
