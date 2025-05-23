![status](https://github.com/toby1984/maven-dependency-version-enforcer/actions/workflows/maven.yml/badge.svg)

# What's this?

A custom maven-enforcer plugin rule that makes sure the direct dependencies a project uses are not "too much" behind 
their respective latest release (in terms of days/weeks/months).   

This rule can be configured to just print warnings about outdated artifacts or outright fail the build. 
Specific artifact IDs/group IDs/version numbers can be excluded from checking using an XML configuration file with the
same syntax that the [maven-versions-plugin](https://www.mojohaus.org/versions-maven-plugin/rule.html) uses.

![screenshot](https://github.com/toby1984/maven-dependency-version-enforcer/blob/master/screenshot.png)

Artifact release information can either be retrieved & stored locally or one can deploy a simple Java servlet on a
server (or simply build & run a docker container using the docker/run_container.sh script in this repository) and have
all clients talk to this servlet instead of talking to Maven Central directly (which is the recommended way to use 
this project as it otherwise creates unnecessary load on Maven Central when multiple people inside the same 
company use it).

Note that metadata.xml files stored on Maven Central do not reveal when a given version has been uploaded so my 
enforcer rule simply scrapes the "last modified" date from an artifact's "Browse" page.

# Basic Usage

Note that you'll need at least JDK 17 to use this enforcer rule.

Do not forget to also add the de.code-sourcery.versiontracker:versiontracker-enforcerrule dependency to the enforcer plugin as otherwise the rule implementation will not be available.

                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-enforcer-plugin</artifactId>
                    <version>3.4.1</version>
                    <executions>
                        <execution>
                            <id>enforce-versions</id>
                            <phase>compile</phase>
                            <goals>
                                <goal>enforce</goal>
                            </goals>
                            <configuration>
                                <rules>
                                    <dependencyAgeRule implementation="de.codesourcery.versiontracker.enforcerrule.DependencyAgeRule">
                                        <!-- Uncomment the next line and adjust the URL to meet your setup if you've deployed the servlet -->
                                        <!-- <apiEndpoint>https://some.host/versiontracker/api</apiEndpoint> -->
                                        <warnAge>1d</warnAge>
                                        <maxAge>12w</maxAge>
                                        <failOnMissingArtifacts>false</failOnMissingArtifacts>
                                        <searchRulesInParentDirectories>true</searchRulesInParentDirectories>
                                        <rulesFile>${project.basedir}/dependency_update_rules.xml</rulesFile>
                                    </dependencyAgeRule>
                                </rules>
                            </configuration>
                        </execution>
                    </executions>
                    <dependencies>
                        <dependency>
                            <groupId>de.code-sourcery.versiontracker</groupId>
                            <artifactId>versiontracker-enforcerrule</artifactId>
                            <version>1.0.29/version>
                        </dependency>
                    </dependencies>
                </plugin>

# Rule Configuration

Note that at least the warnAge (print warning but don't fail the build) or maxAge (fail build) configuration options always need to be present.

| Property Name                  | Values                                                                                                  | Description                                                                                                                                                                                            |
|--------------------------------|---------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| warnAge                        | Xd, X day, X days, X week, X weeks, X month, X months, X year, X years (with X being an integer number) | (optional) Print a warning for each artifact that is more than X behind the latest release. |
| maxAge                         | Xd, X day, X days, X week, X weeks, X month, X months, X year, X years (with X being an integer number) | (optional) Fail the build as soon as any artifact is more than X behind the latest release. |
| apiEndpoint                    | URL                                                                                                     | (optional) Path to API servlet                                                                                                                                                                         |
| failOnMissingArtifacts         | true, false                                                                                             | (optional) Whether to fail the build if no release date could be determined for an artifact. Disabled by default                                                                                                            |
| searchRulesInParentDirectories | true, false                                                                                             | (optional) Whether to search the rules file in parent directories if it can't be found in the current project's directory. Disabled by default.                                                                              |
 | rulesFiles                     | path to XML file                                                                                        | (optional) Path a rules file (using the [maven-versions-plugin](https://www.mojohaus.org/versions-maven-plugin/rule.html) syntax) that describes which versions/artifacts are excluded from age checks |
 | debug                          | true, false                                                                                             | (optional) Enables additional debugging output. Disabled by default.                                                                                                                                                         |
 | verbose                        | true, false                                                                                             | (optional) Enable more verbose output. Disabled by default.                                                                                                                                                                  |

# Building

You will need JDK 17 or later as well as Maven 3.5.2 or later. You'll need to set your MAVEN_OPTS to include "---add-opens java.base/java.lang=ALL-UNNAMED" because otherwise some of the more ancient Maven plugins in this pom.xml will crash

## Servlet setup (optional)

### If you don't want to use the servlet, just omit the `<apiEndpoint>` tag inside the rule configuration.

By default the servlet will store all retrieved artifact metadata as a binary file in ${user.home}/artifacts.json.binary unless you override this location by passing a '-Dversiontracker.artifact.file='&lt;path to file&gt;' option to the JVM (the Dockerfile will use the path /data/artifacts.json.binary so mounting a Docker volume on /data, as the run_container.sh script does, will make the artifact metadata persistent outside of the container).

Note: You can request status information by sending a HTTP GET request to the HTTP endpoint where you deployed the servlet (default is HTML output but you can append "?json" to the URL to get a JSON response).

### Servlet configuration

The servlet supports some basic configuration that can be passed via a properties file pointed to by the 'versionTracker.configFile' system property.
The configuration file currently supports the following keys:

| Key                        | Value                                      | Description                                                                                                                                                              |
|----------------------------|--------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| dataStorage                | filesystem, path                           | Points to file where artifact metadata should be stored                                                                                                                  | 
| updateDelayAfterFailure    | duration                                   | How long to wait before attempting to fetch artifact metadata after the previous attempt failed                                                                          |
| minUpdateDelayAfterSuccess | duration                                   | How long to wait before attempting to fetch artifact metadata after the previous attempt succeeded                                                                       |
| bgUpdateCheckInterval      | duration                                   | How long to wait before checking whether cached artifact metadata is stale and should be refreshed                                                                       |
| blacklistedGroupIds        | comma-separated list of artifact group IDs | Artifact group IDs for which metadata should never be retrieved from Maven Central. Group IDs are prefix matches so 'com.test' will also match 'com.test.something' etc. | 

Durations need to satisfy the regex `^[0-9]+[smhd]$` where s=seconds, m=minutes, h=hours, d=days.
The servlet uses a background thread that periodically iterates over all cached artifact metadata and checks whether it needs to be refreshed from Maven Central again. 

![screenshot](https://github.com/toby1984/maven-dependency-version-enforcer/blob/master/server_screenshot.png)

### Using Docker

You can create/run the servlet inside an Apache Tomcat Docker image using the docker/build_image.sh and docker/run_container.sh scripts (run_container.sh will automatically build the image if not already done).
The run_container.sh script will automatically create a 'versiontracker_data' Docker volume and mount it on /data, making already fetched artifact metadata persistent.

### Using your own application server
Currently there's nothing to be done except running 'mvn package' and then tossing the server/target/versiontracker.war file into the webapps folder of your favorite application server.

## [optional] Create a XML file describing which versions to blacklist

The rules XML file as described here: https://www.mojohaus.org/versions-maven-plugin/rule.html

    <?xml version="1.0" ?>
    <ruleset comparisonMethod="maven">
      <ignoreVersions>
        <ignoreVersion type="regex">20.*</ignoreVersion>
        </ignoreVersions>
      <rules>
        <rule groupId="de.codesourcery" comparisonMethod="maven" >
          <ignoreVersions>
            <ignoreVersion type="regex">.*</ignoreVersion>
          </ignoreVersions>
        </rule>
        <rule groupId="commons-lang" artifactId="commons-lang" comparisonMethod="maven" >
          <ignoreVersions>
            <ignoreVersion type="regex">2003.*</ignoreVersion>
          </ignoreVersions>
        </rule>
      </rules>
    </ruleset>

# API compatibility

Older clients will always be compatible with more recent server versions. A more recent client trying to talk to 
an older server will FAIL (as the server does not know about the version number the client will be presenting.)

# JSON API

While the enforcer rule by default speaks a binary protocol (much faster than jackson databind), the servlet also supports JSON queries using HTTP POST requests to

     http(s)://<your host>[:<your port>]/<webapp name>/api

## Example query

    {
       "clientVersion":"2.0",
       "command":"query",
       "artifacts":[
          {
             "groupId":"org.junit.jupiter",
             "version":"5.9.0-M1",
             "artifactId":"junit-jupiter-api",
             "type":"jar"
          },
          {
             "groupId":"org.junit.jupiter",
             "version":"5.9.0-M1",
             "artifactId":"junit-jupiter",
             "type":"jar"
          }
       ],
       "blacklist":{
          "globalIgnores":[
             {
                "pattern":"(?i).*incubating.*",
                "type":"regex"
             },
             {
                "pattern":"(?i).*atlassian.*",
                "type":"regex"
             }
          ],
          "groupIdIgnores":{
             "org.apache.tomcat":[
                {
                   "pattern":"10\\..*",
                   "type":"regex"
                }
             ],
             "org.wicketstuff":[
                {
                   "pattern":"9\\..*",
                   "type":"regex"
                }
             ]
          },
          "artifactIgnores":{
             "org.snmp4j":{
                "snmp4j-agentx":[
                   {
                      "pattern":".*",
                      "type":"regex"
                   }
                ],
                "snmp4j-agent":[
                   {
                      "pattern":".*",
                      "type":"regex"
                   }
                ],
                "snmp4j":[
                   {
                      "pattern":".*",
                      "type":"regex"
                   }
                ]
             },
             "commons-logging":{
                "commons-logging-api":[
                   {
                      "pattern":"99.0-does-not-exist",
                      "type":"exact"
                   }
                ],
                "commons-logging":[
                   {
                      "pattern":"99.0-does-not-exist",
                      "type":"exact"
                   }
                ]
             }
          }
       }
    }

## Example response 

    {
       "serverVersion":"2.0",
       "command":"query",
       "artifacts":[
          {
             "artifact":{
                "groupId":"org.junit.jupiter",
                "version":"5.9.0-M1",
                "artifactId":"junit-jupiter-api",
                "type":"jar"
             },
             "currentVersion":{
                "versionString":"5.9.0-M1",
                "releaseDate":"202205151838",
                "firstSeenByServer":"202205151838"
             },
             "secondLatestVersion":{
                "versionString":"5.11.0-RC1",
                "releaseDate":"202407311106",
                "firstSeenByServer":"202407311106"
             },
             "latestVersion":{
                "versionString":"5.12.0-M1",
                "releaseDate":"202501311614",
                "firstSeenByServer":"202501311614"
             },
             "updateAvailable":"YES"
          },
          {
             "artifact":{
                "groupId":"org.junit.jupiter",
                "version":"5.9.0-M1",
                "artifactId":"junit-jupiter",
                "type":"jar"
             },
             "currentVersion":{
                "versionString":"5.9.0-M1",
                "releaseDate":"202205151838",
                "firstSeenByServer":"202205151838"
             },
             "secondLatestVersion":{
                "versionString":"5.11.0-RC1",
                "releaseDate":"202407311106",
                "firstSeenByServer":"202407311106"
             },
             "latestVersion":{
                "versionString":"5.12.0-M1",
                "releaseDate":"202501311614",
                "firstSeenByServer":"202501311614"
             },
             "updateAvailable":"YES"
          }
       ]
    }

# Releasing to Maven Central

   mvn -Prelease release:prepare release:perform

# Known bugs

* Adding XML namespace attributes to the &lt;ruleset&gt; tag inside the rules XML currently breaks JAXB unmarshalling,just leave them out for now
 
# Potential enhancements 

* Add support for querying multiple maven repositories instead of just Maven central
* Refactor code to not hold all the metadata in memory (fast and easy but obviously doesn't scale)
* SOAP/JSON interfaces use same entities as database backend ; this makes for concise code but also makes it hard to evolve the
  backend without breaking the external API or having to transmit superfluous data -> separate DTOs are needed here 
* Add SQL database support for metadata storage
* Add module.info descriptors to all sub-modules
