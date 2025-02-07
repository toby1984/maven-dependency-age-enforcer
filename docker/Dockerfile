FROM tomcat:9

ENV CATALINA_OPTS="-Dversiontracker.artifact.file=/data/artifacts.json.binary -Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=*:8889"
RUN mkdir /data
COPY war_temp/*.war /usr/local/tomcat/webapps
ENV JAVA_OPTS="-Dlog4j2.debug"
