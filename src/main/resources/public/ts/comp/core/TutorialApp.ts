import { ReactNode } from "react";
import { Comp } from "../base/Comp";
import { Anchor } from "./Anchor";
import { Button } from "./Button";
import { Checkbox } from "./Checkbox";
import { Div } from "./Div";
import { Li } from "./Li";
import { Ul } from "./Ul";

interface LS { // Local State
    content?: string;
}

/*
This is a Simple app that will be running at `http://localhost:8182/demo/tutorial`, which shows a bare minimum
example of using the Quanta React Framework.
*/
export class TutorialApp extends Comp {
    private static checkboxVal: boolean = false;

    constructor() {
        super();
        this.mergeState<LS>({ content: "Quanta GUI Framework works!" });

        setTimeout(() => {
            let ref = this.getRef();
            if (!ref) {
                console.log("Unable to get ref.");
            }
            else {
                console.log("ref loaded ok");
            }
        }, 1000);
    }

    buttonClick = (): void => {
        this.mergeState<LS>({ content: "You clicked a button!" });
    }

    compRender = (): ReactNode => {
        return this.tag("div", null, [
            this.getState<LS>().content,
            new Div(null, null, [
                new Anchor("https://drudgereport.com", "My Link")
            ]),
            new Div("Child Div", null, [
                new Ul("Unordered List", null, [
                    new Li("item 1"),
                    new Li("item 2"),
                    new Li("item 3")
                ]),
                new Div("SubDiv2"),
                new Div("SubDiv3"),
                new Checkbox("Test Checkbox", null, {
                    setValue: (checked: boolean): void => {
                        TutorialApp.checkboxVal = checked;
                        console.log("checkbox=" + TutorialApp.checkboxVal)
                    },
                    getValue: (): boolean => {
                        return TutorialApp.checkboxVal;
                    }
                })
            ]),
            new Button("My Button", this.buttonClick)
        ]);
    }
}