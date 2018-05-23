# What's this?

A custom rule implementation for the maven-enforcer plugin that enforces dependency versions used by a project are not older than a set duration. Requires running a custom smart proxy (a simple Java servlet) that provides release dates for each artifact version (among other things).

# Motivation

At work we have a fairly large multi-module project (600kloc, 40 modules, ~100 dependencies). After going through a very painful version upgrade after neglecting this for years (literally...) I wanted to prevent this from happening again.

I immediately thought about using the maven-enforcer and maven-versions plugins for this BUT 

1. enforcer plugin cannot enforce dependency versions based on age compared to the artifact currently in use
2. versions plugin can retrieve the latest version for each dependency but does not return the release (upload) date associated with each version...
3. pointing the maven-versions plugin to our internal Artifactory server turned out to be dead-slow (takes a few minutes to check all 100 dependencies) because our artifactory server in turn has a large (>20) number of external repositories configured.

# How does it work?

## Solving the "release date unavailable" and "speed too slow" issues

### Note that as of now (2018-03-28) running the servlet is __optional__ , ommitting the *apiEndpoint* plugin configuration parameter will make the custom rule fetch & store artifact metadata locally (by default in ~/.m2/artifacts.json). I still STRONGLY recommend running the servlet if you have a lot of machines doing builds with this rule as it will otherwise send a LOT of requests against Maven central.

I wrote a custom servlet that 'speaks' JSON and in the background fetches the maven-metadata.xml from a repository AND scrapes the upload dates from the HTML index page generated by at least maven central.
The servlet automatically remembers each artifact query (and the retrieved version information) and will automatically poll the remote repository in configurable time intervals to see whether new versions have been uploaded. This polling happens asynchronously in the background only so unless an artifact is requested for the first time, clients will never have to wait for the information to become available.

The servlet implements a simple HTTP POST+JSON protocol ; a Java client library is included and used by the custom enforcer rule to talk to the servlet.

## Solving the 'maven-enforcer-plugin cannot enforce dependency versions based on age' issue

This is solved by a custom enforcer rule that accepts a configurable maxAge for dependencies and will automatically fail the build if a project dependency is more than 'maxAge' behind the latest available version.

The custom enforcer rule also supports an optional XML file to blacklist specific versions for certain artifacts ; for ease of use this file uses the same syntax as the maven-versions-plugin (see http://www.mojohaus.org/versions-maven-plugin/xsd/rule-2.0.0.xsd)

# Building

You will need JDK 8+ and Maven 3.5.2+
 
# Installation

## Servlet setup

Currently there's nothing to be done except running 'mvn package' and then tossing the server/target/versiontracker.war file inside the webapps folder of your favorite application server.

By default the servlet will store all retrieved artifact metadata as a simple JSON file. This file gets stored as ${user.home}/artifacts.json unless you override this location by passing a '-Dversiontracker.artifact.file='&lt;path to file&gt;' option to the application. 

## Add the custom rule to your enforcer plugin configuration

    <build>
      <plugins>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-enforcer-plugin</artifactId>
          <version>${enforcer.plugin.version}</version>
          <executions>
            <execution>
              <id>enforce-clean</id>
              <phase>clean</phase>
              <goals>
                <goal>enforce</goal>
              </goals>
            </execution>
            <execution>
              <id>enforce-compile</id>
              <phase>compile</phase>
              <goals>
                <goal>enforce</goal>
              </goals>
            </execution>
          </executions>
          <configuration>
            <rules>
              <dependencyAgeRule implementation="de.codesourcery.versiontracker.enforcerrule.DependencyAgeRule">
                <apiEndpoint>http://localhost:8080/versiontracker-server/test</apiEndpoint>
                <warnAge>4d</warnAge>
                <maxAge>2m</maxAge>
                <debug>false</debug>
                <verbose>true</verbose>
                <rulesFile>${project.basedir}/rules.xml</rulesFile>
              </dependencyAgeRule>
            </rules>
          </configuration>
            <dependencies>
              <dependency>
                <groupId>de.codesourcery.versiontracker</groupId>
                <artifactId>versiontracker-enforcerrule</artifactId>
                <version>0.0.3</version>
                <classifier>jdk8</classifier> <!-- classifier needs to match the JDK version you're using -->
              </dependency>
            </dependencies>
        </plugin>
      </plugins>
    </build>
    
# Rule configuration options

| Configuration                  | optional? | Default value |Examples                      | Description |
|--------------------------------|-----------|:-------------:|------------------------------|-------------|
| warnAge                        |     X     |               | 3 days                       | Print a warning for each dependency that was released more than X (days|weeks|months|years) ago. |
| maxAge                         |           |               | 1 month                      | Fail the build for each dependency that was released more than X (days|weeks|months|years) ago. |
| apiEndpoint                    |     X     |               | http://my.server/proxy       | URl to proxy servlet ; if omitted everything will be executed on the client and retrieved metadata is stored in ~/.m2/artifacts.json | 
| verbose                        |     X     |    false      | true                         | Enable printing more verbose information about what's going on |
| debug                          |     X     |    false      | false                        | Enable printing more verbose information about what's going on |
| rulesFile                      |     X     |               | ${project.basedir}/rules.xml | XML file describing which version numbers/artifacts to ignore when checking for updates |
| failOnMissingArtifacts         |     X     |    **true**      | true                         | Whether to fail the build if no release information could be obtained for an artifact |
| searchRulesInParentDirectories |     X     |    false      | true                         | When set to 'true' and the rules file could not be found in the given location, recursively traverse parent directories looking for the file there |


NOTE: The 'searchRulesInParentDirectories' option is a hack to share a single XML rules file in a top-level folder across child modules somewhere below  this folder.

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

# Known bugs

* Adding XML namespace attributes to the &lt;ruleset&gt; tag inside the rules XML currently breaks JAXB unmarshalling,just leave them out for now
 
# Known issues / TODO

* (highest prio) Fix age calculation to always consider the release following the currently in-use one as the reference point, do not unconditionally use the latest release here 

* (high prio) Add support for querying multiple maven repositories instead of just Maven central
* (high prio) Refactor code to not hold all the metadata in memory (fast and easy but obviously doesn't scale)
* (medium prio) Add simple web UI to configure the servlet/query artifacts/show status
* (medium prio) Add SQL database support for metadata storage
* (low prio) Add module descriptors to all sub-modules (I would've done it already but currently the Java ecosystem seems to suffer from a chicken-and-egg problem, everybody's waiting on everybody else to modularize their project first and I got some issues with Eclipse Phonton M6 and missing 'automatic-module-name' entries in various manifests) 
* (low prio,maybe not a good idea) Maybe add support for having per-artifact maxAge / warnAge values inside blacklist XML ?
