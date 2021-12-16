import { ReactNode } from "react";
import { Comp } from "../base/Comp";

interface LS {
    visible: boolean;
    disabled: boolean;
}

export class IconButton extends Comp {

    constructor(public iconClass: string = "", public text: string, attribs: Object = {}, private specialClasses: string = "btn-secondary", private toggle: string = "", private imageUrl: string = null) {
        super(attribs);
        this.attribs.type = "button";
        // somehow this 'clickable' class seems to have no effect
        this.attribs.className = "btn align-middle clickable " + specialClasses;
    }

    compRender(): ReactNode {
        let state = this.getState<LS>();
        this.attribs.style = { display: (state.visible && !state.disabled ? "" : "none") };

        let iconClazz: string = "fa " + this.iconClass;
        if (this.toggle === "on") {
            iconClazz += " iconToggleOn";
        }
        else if (this.toggle === "off") {
            iconClazz += " iconToggleOff";
        }
        else {
            iconClazz += " iconToggleOff";
        }

        let children = [];
        if (this.imageUrl) {
            children.push(this.e("img", {
                key: "s_img_" + this.getId(),
                src: this.imageUrl
            }));
        }

        children.push(this.e("i", {
            key: "i_" + this.getId(),
            className: iconClazz
        }));

        if (this.text) {
            children.push(
                this.e("span", {
                    key: "s_txt_" + this.getId(),
                    className: "icon-button-font"
                }, this.text));
        }
        return this.e("button", this.attribs, children);
    }
}