FROM tomcat:9

ENV CATALINA_OPTS="-Dversiontracker.artifact.file=/data/artifacts.json.binary"
RUN mkdir /data
COPY war_temp/*.war /usr/local/tomcat/webapps
