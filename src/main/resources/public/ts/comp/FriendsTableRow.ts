import { getAs } from "../AppContext";
import { Div } from "../comp/core/Div";
import { Img } from "../comp/core/Img";
import { LS as FriendsDlgState } from "../dlg/FriendsDlg";
import { UserProfileDlg } from "../dlg/UserProfileDlg";
import { FriendInfo } from "../JavaIntf";
import { S } from "../Singletons";
import { CompIntf } from "./base/CompIntf";
import { Checkbox } from "./core/Checkbox";
import { Icon } from "./core/Icon";
import { ListBoxRow } from "./ListBoxRow";

export class FriendsTableRow extends ListBoxRow {

    constructor(public friend: FriendInfo, private selectableRows: boolean, private dlg: CompIntf) {
        super(null, null, null);
        this.attribs.className = "personsListItem";
    }

    preRender(): void {
        const ast = getAs();
        let src: string = null;

        // local users will have this kind of avatar
        if (this.friend.avatarVer) {
            src = S.render.getAvatarImgUrl(this.friend.userNodeId, this.friend.avatarVer);
        }
        // foreign users have this kind of avatar
        else if (this.friend.foreignAvatarUrl) {
            src = this.friend.foreignAvatarUrl;
        }
        else {
            console.log("no avatarVer on friend: " + this.friend.userNodeId);
        }

        const tagsSuffix = this.friend.tags ? (" (" + this.friend.tags + ")") : "";

        this.setChildren([
            new Div(null, null, [
                this.selectableRows ? new Checkbox(null, { className: "personsListItemCheckBox" }, {
                    setValue: (checked: boolean) => {
                        const state: FriendsDlgState = this.dlg.getState();
                        if (checked) {
                            state.selections.add(this.friend.userName);
                        }
                        else {
                            state.selections.delete(this.friend.userName);
                        }
                        this.dlg.mergeState(state);
                    },
                    getValue: (): boolean => this.dlg.getState().selections.has(this.friend.userName)
                }) : null,
                new Div(null, { className: "friendListImgDivCont" }, [
                    new Div(null, { className: "friendListImgDiv centerChild" }, [
                        src ? new Img({
                            className: "friendListImage",
                            src,
                            onClick: () => new UserProfileDlg(this.friend.userNodeId).open()
                        }) : null
                    ])
                ]),
                new Div(null, {
                    className: "friendListText",
                    onClick: () => new UserProfileDlg(this.friend.userNodeId).open()
                }, [
                    new Div(this.friend.displayName),
                    new Div("@" + this.friend.userName + tagsSuffix)
                ]),
                this.friend.liked ? new Icon({
                    title: "This person Liked the Node",
                    className: "fa fa-star fa-lg marginTop marginRight float-end " +
                        (this.friend.userName === ast.userName ? "likedByMeIcon" : "")
                }) : null
            ])
        ]);
    }
}
