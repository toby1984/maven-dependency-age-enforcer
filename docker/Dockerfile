FROM tomcat:11-jdk25

ENV CATALINA_OPTS="-DversionTracker.configFile=/data/versionTracker.properties -Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=*:8889"
RUN mkdir /data
COPY war_temp/*.war /usr/local/tomcat/webapps
ENV JAVA_OPTS="-Dlog4j2.debug"

COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

ENTRYPOINT ["/entrypoint.sh"]
# MUST have explicit CMD here because
# otherwise entrypoint.sh (which ends with exec "$@")
# does not know what to run and the container will terminate 
# immediately.
CMD ["catalina.sh", "run"]
