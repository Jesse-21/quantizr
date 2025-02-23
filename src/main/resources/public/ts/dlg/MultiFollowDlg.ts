import { ScrollPos } from "../comp/base/Comp";
import { CompIntf } from "../comp/base/CompIntf";
import { Button } from "../comp/core/Button";
import { ButtonBar } from "../comp/core/ButtonBar";
import { Div } from "../comp/core/Div";
import { TextArea } from "../comp/core/TextArea";
import { TextContent } from "../comp/core/TextContent";
import { DialogBase } from "../DialogBase";
import * as J from "../JavaIntf";
import { S } from "../Singletons";
import { Validator, ValidatorRuleName } from "../Validator";

export class MultiFollowDlg extends DialogBase {
    userNamesState: Validator = new Validator("", [{ name: ValidatorRuleName.REQUIRED }]);
    textScrollPos = new ScrollPos();

    constructor() {
        super("Follow Multiple Accounts", "app-modal-content-medium-width");
        this.validatedStates = [this.userNamesState];
    }

    renderDlg(): CompIntf[] {
        return [
            new Div(null, null, [
                new TextContent("Enter Fediverse Names (one per line)"),
                new TextArea("User Names", { rows: 15 }, this.userNamesState, null, false, this.textScrollPos),
                new ButtonBar([
                    new Button("Follow All", this.follow, null, "btn-primary"),
                    new Button("Close", this.close, null, "btn-secondary float-end")
                ], "marginTop")
            ])
        ];
    }

    follow = async () => {
        await S.rpcUtil.rpc<J.AddFriendRequest, J.AddFriendResponse>("addFriend", {
            userName: this.userNamesState.getValue()
        });
        this.close();
    }
}
