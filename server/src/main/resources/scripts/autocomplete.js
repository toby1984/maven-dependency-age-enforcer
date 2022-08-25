// original code taken from https://www.w3schools.com/howto/howto_js_autocomplete.asp
// and then modernized, changed to asynchronously fetch completions and support offering completion on empty input as well

// the autocomplete function accepts three arguments:
// 1. the text field element
// 2. a predicate function that will receive the user input as its only argument and
//    must return either true or false depending on whether auto-completion should trigger
// 3. A function that will receive the user input as its only argument and must return
//    a ** Promise ** that yields an array-like (never NULL) with the available auto-completions
function autocomplete(inp, activationPredicate, choiceSupplier)
{
    let currentFocus;
    // execute a function when someone writes in the text field:
    let onChangeFunction = function (e, closeExisting) {
        const val = this.value;

        // close any already open lists of autocompleted values
        if ( closeExisting == null || closeExisting === true ) {
            closeAllListsUnconditionally();
        }

        if ( ! activationPredicate(val) ) {
            return false;
        }
        currentFocus = -1;
        // create a DIV element that will contain the items (values):
        const a = document.createElement("div");
        a.id = this.id + "autocomplete-list";
        a.classList.add( "autocomplete-items");
        a.setAttribute("data-autocomplete-for", inp.id);
        // append the DIV element as a child of the autocomplete container
        this.parentNode.appendChild(a);
        //for each item in the array...
        choiceSupplier(val).then( function(arr) {
            for (let i = 0; i < arr.length; i++) {
                // create a DIV element for each matching element:
                const b = document.createElement("div");
                // make the matching letters bold:
                if ( val && val.length > 0 ) {
                    const idx = arr[i].indexOf(val);
                    let part1='',part2='',part3='';
                    if ( idx > 0 ) {
                        part1 = arr[i].substring(0,idx);
                    }
                    part2 = "<strong>" + arr[i].substring(idx,idx+val.length) + "</strong>";
                    if ( (idx+val.length) < arr[i].length ) {
                        part3 = arr[i].substring(idx + val.length);
                    }
                    b.innerHTML = part1 + part2 + part3;
                } else {
                    b.innerHTML = arr[i];
                }
                // insert a input field that will hold the current array item's value:
                b.innerHTML += "<input type='hidden' value='" + arr[i] + "'>";
                // execute a function when someone clicks on the item value (DIV element):
                b.addEventListener("click", function (e) {
                    // insert the value for the autocomplete text field:
                    inp.value = this.getElementsByTagName("input")[0].value;
                    // close the list of autocompleted values,
                    // (or any other open lists of autocompleted values:
                    closeAllListsUnconditionally();
                    e.stopPropagation();
                    e.preventDefault();
                });
                a.appendChild(b);
            }
        });
    };
    inp.addEventListener("input", function(e) {
        onChangeFunction.apply(this,e,false)
    });
    inp.addEventListener("focus", function(e) {
        onChangeFunction.apply(this, e,true);
    });
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
            // If the ENTER key is pressed, prevent the form from being submitted,
            e.preventDefault();
            if (currentFocus > -1) {
                // and simulate a click on the "active" item:
                if (x) x[currentFocus].click();
            }
        }
    });

    function markItemActive(x) {
        if (!x) {
            return false;
        }
        // start by removing the "active" class on all items:
        markItemInactive(x);
        if (currentFocus >= x.length) {
            currentFocus = 0;
        }
        if (currentFocus < 0) {
            currentFocus = (x.length - 1);
        }
        x[currentFocus].classList.add("autocomplete-active");
    }

    function markItemInactive(x) {
        // a function to remove the "active" class from all autocomplete items:
        for (let i = 0; i < x.length; i++) {
            x[i].classList.remove("autocomplete-active");
        }
    }

    function closeAllListsUnconditionally() {
        // close all autocomplete lists in the document,
        const x = document.getElementsByClassName("autocomplete-items");
        for (let i = 0; i < x.length; i++) {
            x[i].parentNode.removeChild(x[i]);
        }
    }

    document.addEventListener("click", function (e) {
        // close all EXCEPT the currently active list
        const x = document.getElementsByClassName("autocomplete-items");
        for (let i = 0; i < x.length; i++) {
            const item = x[i];
            const ownerid = item.getAttribute("data-autocomplete-for");
            const owningElement = document.getElementById(ownerid);
            if ( owningElement != document.activeElement ) {
                item.remove();
            }
        }
    });
}