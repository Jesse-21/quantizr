import { CompIntf } from "../comp/base/CompIntf";
import { Button } from "../comp/core/Button";
import { ButtonBar } from "../comp/core/ButtonBar";
import { Clearfix } from "../comp/core/Clearfix";
import { Div } from "../comp/core/Div";
import { Span } from "../comp/core/Span";
import { TextField } from "../comp/core/TextField";
import { Constants as C } from "../Constants";
import { DialogBase } from "../DialogBase";
import * as J from "../JavaIntf";
import { S } from "../Singletons";
import { Validator, ValidatorRuleName } from "../Validator";
import { ConfirmDlg } from "./ConfirmDlg";
import { ResetPasswordDlg } from "./ResetPasswordDlg";

export class LoginDlg extends DialogBase {
    userState: Validator = new Validator("", [{ name: ValidatorRuleName.REQUIRED }]);
    pwdState: Validator = new Validator("", [{ name: ValidatorRuleName.REQUIRED }]);

    constructor() {
        super("Login", "app-modal-content-narrow-width");
        this.validatedStates = [this.userState, this.pwdState];
    }

    renderDlg(): CompIntf[] {
        return [
            new Div(null, { c: "LoginContainerDiv" }, [
                new TextField({ label: "User", enter: this.login, val: this.userState }),
                new TextField({ label: "Password", pwd: true, enter: this.login, val: this.pwdState }),
                new Div(null, { className: "marginTop marginBottom" }, [
                    new Span("Reset Password", { className: "clickable float-end", onClick: this.resetPassword }),
                    new Clearfix()
                ])
            ]),
            new ButtonBar([
                new Button("Login", this.login, null, "btn-primary"),
                new Button("Close", () => {
                    this.close();
                    S.util.loadAnonPageHome();
                }, null, "btn-secondary float-end")
            ], "marginTop")
        ];
    }

    preLoad = async () => {
        const user = await S.localDB.getVal(C.LOCALDB_LOGIN_USR);
        const pwd = await S.localDB.getVal(C.LOCALDB_LOGIN_PWD);

        this.userState.setValue(user);
        this.pwdState.setValue(pwd);
    }

    login = async () => {
        let usr = this.userState.getValue();

        /* The word admin is not a secret so let's make it easy for the admin to login using only his password */
        if (usr === "a") {
            usr = J.PrincipalName.ADMIN;
        }

        // if the password field is empty, or CTRL key is down, and a username is provided, then get the password from the browser
        // and ignore the password field.
        if ((!this.pwdState.getValue() || S.util.ctrlKeyCheck()) && usr) {
            // this is kind of ugly but we need to set localDb userName for keys to generate properly
            S.localDB.userName = usr;

            // lookup the password based on known user
            const pwd = await S.localDB.getVal(C.LOCALDB_LOGIN_PWD, usr);
            if (pwd) {
                // put password in the password field
                this.pwdState.setValue(pwd);
                console.log("Got password from LOCAL storage.");
            }
        }

        if (!this.validate()) {
            return;
        }

        const pwd = this.pwdState.getValue();
        if (usr && pwd) {
            if (S.crypto.avail) {
                await S.crypto.initKeys(usr, false, false, false, "all");
            }
            const res = await S.rpcUtil.rpc<J.LoginRequest, J.LoginResponse>("login", {
                userName: usr,
                password: pwd,
                tzOffset: new Date().getTimezoneOffset(),
                dst: S.util.daylightSavingsTime,
                sigKey: S.quanta.sigKey,
                asymEncKey: S.quanta.asymEncKey
            }, false, true);

            if (res.success) {
                S.quanta.authToken = res.authToken;
                S.user.loginResponse(res, usr, pwd, true);
                this.close();
            }
        }
    }

    resetPassword = async (): Promise<any> => {
        const usr = this.userState.getValue();
        const dlg = new ConfirmDlg("Reset your password ?", "Confirm");
        await dlg.open();
        if (dlg.yes) {
            this.close();
            new ResetPasswordDlg(usr).open();
        }
    }
}
