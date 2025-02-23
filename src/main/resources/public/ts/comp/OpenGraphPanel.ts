import { getAs } from "../AppContext";
import { CompIntf } from "../comp/base/CompIntf";
import { Anchor } from "../comp/core/Anchor";
import { Div } from "../comp/core/Div";
import { Icon } from "../comp/core/Icon";
import { Img } from "../comp/core/Img";
import { TabIntf } from "../intf/TabIntf";
import * as J from "../JavaIntf";
import { S } from "../Singletons";
import { FlexRowLayout } from "./core/FlexRowLayout";
import { Html } from "./core/Html";

interface LS { // Local State
    og: J.OpenGraph;
    loading?: boolean;
}

export class OpenGraphPanel extends Div {
    loading: boolean;

    constructor(private tabData: TabIntf<any>, key: string, private url: string, private wrapperClass: string,
        private imageClass: string, private showTitle: boolean, private allowBookmarkIcon: boolean, private includeImage: boolean) {
        super(null, {
            title: url,
            key
        });

        /* The state should always contain loading==true (if currently querying the server) or a non-null 'og'. A completed but failed
         pull of the open graph data should result in og being an empty object and not null. */
        const og: J.OpenGraph = S.quanta.openGraphData.get(url);
        if (og) {
            this.mergeState<LS>({ og });
        }
    }

    domAddEvent = () => {
        const elm: HTMLElement = this.getRef();
        if (!elm || !elm.isConnected || this.getState<LS>().og) return;
        const og = S.quanta.openGraphData.get(this.url);
        if (!og) {
            const observer = new IntersectionObserver(entries => //
                entries.forEach(entry => this.processOgEntry(entry, elm)));
            observer.observe(elm.parentElement);
        }
        else {
            this.mergeState<LS>({ og });
        }
    }

    processOgEntry = (entry: any, elm: HTMLElement) => {
        if (!entry.isIntersecting) return;
        const og = S.quanta.openGraphData.get(this.url);
        if (!og) {
            if (!this.loading) {
                this.loading = true;
                S.util.loadOpenGraph(this.url, (og: J.OpenGraph) => {
                    this.loading = false;
                    og = og || {
                        title: null,
                        description: null,
                        image: null,
                        url: null
                    };
                    // observer.disconnect();
                    S.quanta.openGraphData.set(this.url, og);
                    if (!elm.isConnected) return;
                    this.mergeState<LS>({ og });
                });
            }
        }
        else {
            this.mergeState<LS>({ og });
        }
        this.loadNext();
    }

    /* This loads the next upcomming OpenGraph assuming the user is scrolling down. This is purely a
    performance optimization to help the user experience and is not a core part of the logic for
     'correct' functioning, but it does offer an extremely nice smooth experience when scrolling down thru content
     even including content with lots and lots of openGraph queries happening in the background. */
    loadNext = () => {
        let found = false;
        let count = 0;
        if (!this.tabData) return;

        this.tabData.openGraphComps.forEach(o => {
            if (found) {
                /* I think it's counterproductive for smooth scrolling to preload more than one */
                if (count++ < 1) {
                    const og = S.quanta.openGraphData.get(o.url);
                    if (!og) {
                        if (!o.loading) {
                            o.loading = true;
                            S.util.loadOpenGraph(o.url, (og: J.OpenGraph) => {
                                o.loading = false;
                                if (!og) {
                                    og = {
                                        title: null,
                                        description: null,
                                        image: null,
                                        url: null
                                    };
                                }
                                S.quanta.openGraphData.set(o.url, og);
                                if (!o.getRef()) return;
                                o.mergeState({ og });
                            });
                        }
                    }
                    else {
                        o.mergeState({ og });
                    }
                }
            }
            else if (o.getId() === this.getId()) {
                found = true;
            }
        });
    }

    preRender(): void {
        const state = this.getState<LS>();
        const ast = getAs();
        if (state.loading || !state.og) {
            this.setChildren(null);
            return;
        }

        /* If neither a description nor image exists, this will not be interesting enough so don't render */
        if (!state.og.description && !state.og.image) {
            this.setChildren(null);
            return null;
        }

        if (!state.og.url) {
            state.og.url = this.url;
        }

        const bookmarkIcon = this.allowBookmarkIcon && state.og.url && !ast.isAnonUser ? new Icon({
            className: "fa fa-bookmark fa-lg ogBookmarkIcon float-end",
            onClick: () => S.edit.addLinkBookmark(state.og.url, null)
        }) : null;

        if (state.og?.description?.length > 804) {
            state.og.description = state.og.description.substring(0, 800) + "...";
        }

        let imgAndDesc: CompIntf = null;
        if (state.og.image && this.includeImage) {
            // According to my test results this can cause a scrolling glitch, where the browser throws an error and somehow
            // apparently that interfered with rendering. Wasn't able to repro on localhost because of using http I think, so
            // this code is probably harmless even if I'm making a mistake blaming the scrolling glitch on this.
            state.og.image = S.util.replaceAll(state.og.image, "http://", "https://");

            // if mobile portrait mode render image above (not beside) description
            if (ast.mobileMode && window.innerWidth < window.innerHeight) {
                imgAndDesc = new Div(null, null, [
                    new Img({
                        className: "openGraphImageVert",
                        src: state.og.image
                    }),
                    new Div(state.og.description)
                ]);
            }
            else {
                // if we have an image then render a left-hand side and right-hand side.
                imgAndDesc = new FlexRowLayout([
                    !S.quanta.brokenImages.has(state.og.image) ? new Div(null, { className: "openGraphLhs" }, [
                        new Img({
                            className: this.imageClass,
                            src: state.og.image
                        })
                    ]) : null,
                    new Div(null, { className: "openGraphRhs" }, [
                        new Html(state.og.description, { className: "openGraphDesc" })
                    ])
                ]);
            }
        }
        // if no image just display the description in a div
        else {
            imgAndDesc = new Div(null, { className: "openGraphNoImage" }, [
                new Div(state.og.description)
            ]);
        }

        this.attribs.className = this.wrapperClass;
        this.setChildren([
            bookmarkIcon,
            this.showTitle ? (state.og.url ? new Anchor(this.url, state.og.title, {
                target: "_blank",
                className: "openGraphTitle"
            }) : new Div(state.og.title, {
                className: "openGraphTitle"
            })) : null,
            imgAndDesc
        ]);
    }
}
