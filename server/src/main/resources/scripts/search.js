"use strict";

const search = {
    init : function() {
    },
    resetInputFields : function() {
      document.getElementById("searchResults").innerHTML = "";
      ["artifactId","groupId","classifier"].forEach( id => document.getElementById(id).value = null );
    },
    httpGet : function(url, successCallback, errorCallback)
    {
      fetch(url)
        .then((response) => response.json())
        .then(function(data) {
        successCallback(data);
      }).catch(function() {
        if ( errorCallback ) {
          errorCallback();
        }
      });
    },
    performSearch : function() {
      const artifactId = document.getElementById("artifactId").value;
      if ( ! artifactId  || artifactId.trim().length === 0 ) {
        alert("You need to enter an artifact ID.");
        return false;
      }
      const groupId = document.getElementById("groupId").value;
      if ( ! groupId  || groupId.trim().length === 0 ) {
        alert("You need to enter an artifact ID.");
        return false;
      }
      const classifier = document.getElementById("classifier").value;
      const searchURI = "simplequery?artifactId=" + encodeURIComponent(artifactId) + "&groupId=" + encodeURIComponent(groupId) + (classifier ? "&classifier=" + encodeURIComponent(classifier) : "");
      search.httpGet( searchURI, function(json) {
        const container = document.getElementById("searchResults");

        let rows = "";
        if ( json ) {
          rows = json[0].versions.map(version => {
            return "<tr><td>" + version.versionString + "</td><td>" + ( version.releaseDate ? version.releaseDate : "n/a" ) + "</td></tr>"
          }).reduce((previous, current) => previous + current);
        } else {
          rows = "<tr><td colspan='2'>Artifact not found/not queried yet.</td>";
        }
        container.innerHTML = "<table><thead><tr><th>Version</th><th>Release Date</th></tr></thead>"+rows+"</table>";

      }, function() {
        alert("HTTP GET failed");
      });
      return true;
    }
};
search.init();
