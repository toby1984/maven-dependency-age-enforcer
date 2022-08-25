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
// the autocomplete function accepts three arguments:
// 1. the text field element to attach to auto-completer functionality to
//    (note: needs to be wrapped in a <div> with class "autocomplete" to render properly)
// 2. a predicate function that will receive the user input as its only argument and
//    must return either true or false depending on whether auto-completion should trigger
// 3. A function that will receive the user input as its only argument and must return
//    a ** Promise ** that yields an array-like (never NULL) with the available auto-completions
function autocomplete(inp, activationPredicate, choiceSupplier)
{
    let currentFocus;
    let onChangeFunction = function(e) {
        const val = this.value;

        // close any already open lists of autocompleted values
        closeAllListsUnconditionally();

        // check whether auto-completion should trigger at all
        if ( ! activationPredicate(val) ) {
            return false;
        }
        currentFocus = -1;

        const a = document.createElement("div");
        a.id = this.id + "autocomplete-list";
        a.classList.add( "autocomplete-items");
        a.setAttribute("data-autocomplete-for", inp.id);

        this.parentNode.appendChild(a);

        choiceSupplier(val).then( function(arr)
        {
            const quotedVal = escapeHtmlEntities(val);
            for (let i = 0; i < arr.length; i++) {
                // create a DIV element for each matching element
                const b = document.createElement("div");
                // highlight matching part
                const currentValue = escapeHtmlEntities(arr[i]);
                if ( val && val.length > 0 ) {
                    const idx = currentValue.indexOf(quotedVal);
                    let part1='',part2='',part3='';
                    if ( idx > 0 ) {
                        part1 = currentValue.substring(0,idx);
                    }
                    part2 = "<strong>" + currentValue.substring(idx,idx+quotedVal.length) + "</strong>";
                    if ( (idx+quotedVal.length) < currentValue.length ) {
                        part3 = currentValue.substring(idx + quotedVal.length);
                    }
                    b.innerHTML = part1 + part2 + part3;
                } else {
                    b.innerHTML = currentValue;
                }
                b.unescapedValue = arr[i];

                // when clicked, copy selected value to actual input field
                b.addEventListener("click", function (e) {
                    inp.value = this.unescapedValue;
                    closeAllListsUnconditionally();
                    e.preventDefault();
                    e.stopPropagation();
                });
                a.appendChild(b);
            }
        });
    };
    inp.addEventListener("input", onChangeFunction);
    inp.addEventListener("focus", onChangeFunction);

    // execute a function presses a key on the keyboard:
    inp.addEventListener("keydown", function (e) {
        let x = document.getElementById(this.id + "autocomplete-list");
        if (x) {
            x = x.getElementsByTagName("div");
        }
        if (e.keyCode === 40) { // arrow DOWN
            currentFocus++;
            markItemActive(x);
        } else if (e.keyCode === 38) { // arrow UP
            currentFocus--;
            markItemActive(x);
        } else if (e.keyCode === 13) { // enter
            e.preventDefault();
            if (currentFocus > -1) {
                // and simulate a click on the "active" item:
                if (x) {
                    x[currentFocus].click();
                }
            }
        }
    });

    function markItemActive(htmlCollection) {
        if (!htmlCollection) {
            return false;
        }
        markItemInactive(htmlCollection);
        if (currentFocus >= htmlCollection.length) {
            currentFocus = 0;
        }
        if (currentFocus < 0) {
            currentFocus = (htmlCollection.length - 1);
        }
        htmlCollection[currentFocus].classList.add("autocomplete-active");
    }

    function markItemInactive(htmlCollection) {
        for ( let item of htmlCollection ) {
            item.classList.remove("autocomplete-active");
        }
    }

    function closeAllListsUnconditionally() {
        const htmlCollection = document.getElementsByClassName("autocomplete-items");
        for ( let item of htmlCollection ) {
            item.remove();
        }
    }

    function escapeHtmlEntities(str) {
        if ( str == null ) {
            return str;
        }
        const htmlEntities = {
            "&": "&amp;",
            "<": "&lt;",
            ">": "&gt;",
            '"': "&quot;",
            "'": "&apos;"
        };
        return str.replace(/([&<>"'])/g, match => htmlEntities[match]);
    }

    document.addEventListener("click", function (e) {
        // close all EXCEPT the currently active list
        const htmlCollection = document.getElementsByClassName("autocomplete-items");
        for ( let item of htmlCollection ) {
            const ownerid = item.getAttribute("data-autocomplete-for");
            const owningElement = document.getElementById(ownerid);
            if ( owningElement != document.activeElement ) {
                item.remove();
            }
        }
    });
}