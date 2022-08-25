/*
 * Copyright 2018 Tobias Gierke <tobias.gierke@code-sourcery.de>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
        }).catch(function(error) {
      if ( errorCallback ) {
        errorCallback(error);
      }
    });
  },
  artifactIdAutoComplete : function(groupId, userInput) {
    const baseURL = document.getElementById("baseUrl").href;
    const searchURI = baseURL + "/autocomplete?kind=artifactId&groupId="+encodeURIComponent(groupId)+"&userInput=" + encodeURIComponent(userInput);

    return fetch(searchURI)
        .then((response) => response.json())
        .catch(function() {
          alert("REST call failed");
    });
  },
  groupIdAutoComplete : function(userInput) {
    const baseURL = document.getElementById("baseUrl").href;
    const searchURI = baseURL + "/autocomplete?kind=groupId&userInput=" + encodeURIComponent(userInput);
    return fetch(searchURI)
        .then((response) => response.json())
        .catch(function() {
          alert("REST call failed");
        });
  },
  formatDate : function(dateAsUtcString) {
    // input is whatever de.codesourcery.versiontracker.common.JSONHelper.ZonedDateTimeSerializer
    // produces, currently yyyyMMddHHmm in UTC

    if ( ! dateAsUtcString ) {
      return "n/a";
    }

    const year = dateAsUtcString.substring(0, 4);
    const month = dateAsUtcString.substring(4, 6);
    const dayOfMonth = dateAsUtcString.substring(6, 8);
    const hourOfDay = dateAsUtcString.substring(8, 10);
    const minuteOfHour = dateAsUtcString.substring(10, 12);

    return year+"-"+month+"-"+dayOfMonth+" "+hourOfDay+":"+minuteOfHour+" UTC";
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
    const baseURL = document.getElementById("baseUrl").href;
    const searchURI = baseURL+"/simplequery?artifactId=" + encodeURIComponent(artifactId) + "&groupId=" + encodeURIComponent(groupId) + (classifier ? "&classifier=" + encodeURIComponent(classifier) : "");
    search.httpGet( searchURI, function(json) {
      const container = document.getElementById("searchResults");

      let rows = "";
      let table1 = "";
      if ( json && json.length > 0 )
      {
        const item = json[0];
        item.versions.sort( (a,b) => {
            const v1 = a.versionString;
            const v2 = b.versionString;
            if ( v1 != null && v2 != null ) {
              return v2.localeCompare(v1); // reverse sort
            }
            return v1 === v2 ? 0 : 1;
        });
        rows = item.versions.map(version => {
          return "<tr><td>" + version.versionString + "</td><td>" +
              search.formatDate( version.releaseDate ) + "</td></tr>"
        }).reduce((previous, current) => previous + current);

        table1 = "<table>"+
            "<tr><td><b>Last Request Date: </b></td><td>"+search.formatDate( item.lastRequestDate )+"</td></tr>" +
            "<tr><td><b>Creation Date</b>: </td><td>"+search.formatDate( item.creationDate )+"</td></tr>" +
            "<tr><td><b>Last Success Date: </b></td><td>"+search.formatDate( item.lastSuccessDate )+"</td></tr>" +
            "</table>";
      } else {
        rows = "<tr><td colspan='2'>Artifact not found/not queried yet.</td>";
      }
      container.innerHTML = table1+ "<table><thead><tr><th>Version</th><th>Release Date</th></tr></thead>"+rows+"</table>";

    }, function(error) {
      if ( console && console.trace ) {
        console.trace(error);
      }
      alert("HTTP GET failed");
    });
    return true;
  }
};
search.init();
