import { ReactNode } from "react";
import { Comp } from "../base/Comp";
import { CompIntf } from "../base/CompIntf";
import { Div } from "./Div";

// todo-1: finish converting all these to FlexRowLayout
export class HorizontalLayout extends Comp {

    constructor(public comps: CompIntf[] = null, classes: string = "horizontalLayoutComp", attribs: any = {}) {
        super(attribs);
        this.attribs.className = classes;
    }

    compRender = (): ReactNode => {
        if (this.comps) {
            for (const comp of this.comps) {
                if (!comp) continue;

                if (!comp.attribs.className) {
                    comp.attribs.className = "displayCell";
                }
                else {
                    if (comp.attribs.className.indexOf("displayCell") === -1) {
                        comp.attribs.className += " displayCell";
                    }
                }
            }
        }

        return this.tag("div", null, [new Div(null, { className: "displayRow" }, this.comps)]);
    }
}
