import { getAs } from "../AppContext";
import { Comp } from "../comp/base/Comp";
import { Button } from "../comp/core/Button";
import { Div } from "../comp/core/Div";
import { Heading } from "../comp/core/Heading";
import { HelpButton } from "../comp/core/HelpButton";
import { FriendsDlg } from "../dlg/FriendsDlg";
import { SearchUsersDlg } from "../dlg/SearchUsersDlg";
import { TabIntf } from "../intf/TabIntf";
import { NodeActionType } from "../intf/TypeIntf";
import * as J from "../JavaIntf";
import { TypeBase } from "./base/TypeBase";

export class FriendsListType extends TypeBase {
    constructor() {
        super(J.NodeType.FRIEND_LIST, "Friends", "fa-users", false);
    }

    getAllowRowHeader(): boolean {
        return false;
    }

    allowAction(action: NodeActionType, node: J.NodeInfo): boolean {
        return false;
    }

    render = (node: J.NodeInfo, tabData: TabIntf<any>, rowStyling: boolean, isTreeView: boolean, isLinkedNode: boolean): Comp => {
        return new Div(null, { className: "systemNodeContent" }, [
            new HelpButton(() => getAs().config.help?.type?.friendsList?.render, null, "btn-secondary float-end"),
            new Heading(4, "Friends"),
            new Div("These are the people you follow. Delete from this list to unfollow.", { className: "marginAll" }),
            new Button("Add New Friend", () => {
                new SearchUsersDlg().open();
            }, null, "btn-primary"),
            new Button("Search Friends", () => {
                const friendsDlg = new FriendsDlg("Friends", null, true);
                friendsDlg.open();
            }, null, "btn-primary")
        ]);
    }

    isSpecialAccountNode(): boolean {
        return true;
    }

    subOrdinal(): number {
        return 2;
    }
}
