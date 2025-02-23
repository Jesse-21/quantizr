import { getAs } from "../AppContext";
import { Div } from "../comp/core/Div";
import { TabIntf } from "../intf/TabIntf";
import { S } from "../Singletons";
import { Comp } from "./base/Comp";

/* NOTE: All classes derived from AppTab should have each top-level (in the vertical dimension) item having
a class 'data.id' tacked on to it. This class is expected to be there for managing scrolling, and also the
IDs on each of those elements needs to be repeatable across all renders. */
export class AppTab<T = any> extends Div {
    headingBar: Comp = null;

    constructor(public data: TabIntf<T>, private extraEditModeClass: string = null) {
        super(null, {
            id: data.id,
            // tabIndex is required or else scrolling by arrow keys breaks.
            tabIndex: "2"
        });
    }

    getClass = (): string => {
        const ast = getAs();
        const className = (ast.mobileMode ? "my-tab-pane-mobile " : "my-tab-pane ") + "customScrollbar " +
            (ast.userPrefs.editMode && this.extraEditModeClass ? (this.extraEditModeClass) : "") +
            (ast.activeTab === this.getId() ? " visible" : " invisible");
        return className;
    }

    getScrollPos = (): number => {
        return this.data.scrollPos;
    }

    setScrollPos = (pos: number): void => {
        this.data.scrollPos = pos;
    }

    scrollToNode = (nodeId: string): void => {
        const elm = S.domUtil.domElm(S.tabUtil.makeDomIdForNode(this.data, nodeId));
        if (elm) {
            this.scrollToElm(elm);
        }
    }

    scrollToElm = (elm: HTMLElement): void => {
        if (!elm) return;

        // Mobile mode doesn't use 'sticky' header in the tab, so we can scroll to the
        // exact location of offsetTop, without taking into account any sticky header height.
        if (getAs().mobileMode) {
            this.setScrollTop(elm.offsetTop);
        }
        else {
            // headingBar is not fixed height so we get it's hight in realtime here.
            const headingBarHeight = this.headingBar?.getRef()?.offsetHeight || 0;

            // we scroll up the additional 12 pixels just to make a slight gap between top row border
            // and heading border, becasue it's slightly better looking that way.
            let top = elm.offsetTop - headingBarHeight - 12;
            if (top < 0) top = 0;
            this.setScrollTop(top);
        }
    }
}
