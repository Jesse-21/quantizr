import { CompIntf } from "../comp/base/CompIntf";
import { Button } from "../comp/core/Button";
import { ButtonBar } from "../comp/core/ButtonBar";
import { Div } from "../comp/core/Div";
import { TextContent } from "../comp/core/TextContent";
import { DialogBase } from "../DialogBase";

export class ConfirmDlg extends DialogBase {
    yes: boolean = false;

    constructor(private text: string, title: string, private yesButtonClass: string=null, private textClass: string=null, private showNoButton: boolean = true) {
        super(title, "app-modal-content-narrow-width");
    }

    renderDlg(): CompIntf[] {
        return [
            new Div(null, null, [
                new TextContent(this.text, this.textClass),
                new ButtonBar([
                    new Button("Yes", () => {
                        this.yes = true;
                        this.close();
                    }, null, this.yesButtonClass || "btn-primary"),
                    this.showNoButton ? new Button("No", this.close) : null
                ], "marginTop")
            ])
        ];
    }
}
