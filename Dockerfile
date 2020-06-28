FROM maven:3-jdk-11 as build
ENV LANGUAGETOOL_VERSION=5.0
MAINTAINER Bruno Perel <brunoperel@gmail.com>

WORKDIR /srv
COPY . languagetool
RUN cd languagetool && mvn --projects languagetool-standalone,languagetool-wikipedia --also-make package -DskipTests \
 && mkdir /srv/languagetool-runtime && cd /srv/languagetool-runtime && unzip /srv/languagetool/languagetool-standalone/target/LanguageTool-$LANGUAGETOOL_VERSION.zip \
 && rm /srv/languagetool/languagetool-standalone/target/LanguageTool-$LANGUAGETOOL_VERSION.zip


FROM openjdk:11-jre-buster as languagetool-server
COPY --from=build /srv/languagetool-runtime /srv/languagetool-runtime

COPY ./entrypoint-languagetool-server.sh /home/entrypoint.sh
RUN chmod +x /home/entrypoint.sh
ENTRYPOINT ["sh", "-c", "/home/entrypoint.sh"]

EXPOSE 8010


FROM openjdk:11-jre-buster as languagetool-wikipedia
ENV LANGUAGETOOL_VERSION=5.0.0
COPY --from=build /srv/languagetool/languagetool-wikipedia/target/LanguageTool-wikipedia-$LANGUAGETOOL_VERSION /srv/languagetool-wikipedia
COPY ./import-dump.sh /home

RUN chmod +x /home/import-dump.sh \
 && apt-get update && apt-get install --no-install-recommends -y jq curl cron procps libxml-xpath-perl && apt-get clean

RUN echo "0 1 * * * pgrep -f /home/import-dump.sh > /dev/null 2> /dev/null || /home/import-dump.sh &> /proc/1/fd/1" > /etc/cron.d/import
RUN crontab /etc/cron.d/import

CMD ["cron", "-f"]
