import { dispatch, getAs, promiseDispatch } from "./AppContext";
import { Constants as C } from "./Constants";
import { ConfirmDlg } from "./dlg/ConfirmDlg";
import { FriendsDlg } from "./dlg/FriendsDlg";
import { LoginDlg } from "./dlg/LoginDlg";
import { SignupDlg } from "./dlg/SignupDlg";
import * as J from "./JavaIntf";
import { S } from "./Singletons";
import { TrendingView } from "./tabs/TrendingView";

export class User {
    closeAccount = async () => {
        let dlg = new ConfirmDlg("Are you sure you want to close your account?", "Close Account");
        await dlg.open();
        if (!dlg.yes) {
            return;
        }

        dlg = new ConfirmDlg("Your data will be deleted and can never be recovered. Are you sure?", "Close Account");
        await dlg.open();
        if (dlg.yes) {
            await this.deleteAllUserLocalDbEntries();
            await S.rpcUtil.rpc<J.CloseAccountRequest, J.CloseAccountResponse>("closeAccount");

            /* Remove warning dialog to ask user about leaving the page */
            window.onbeforeunload = null;

            /* reloads browser with the query parameters stripped off the path */
            window.location.href = window.location.origin;
        }
    }

    /*
     * for testing purposes, I want to allow certain users additional privileges. A bit of a hack because it will go
     * into production, but on my own production these are my "testUserAccounts", so no real user will be able to
     * use these names
     */
    isTestUserAccount = (): boolean => {
        const lcUserName = getAs().userName.toLowerCase();
        return lcUserName === "adam" || //
            lcUserName === "bob" || //
            lcUserName === "cory" || //
            lcUserName === "dan";
    }

    // returns true if we already initialized to a tab specified on url
    usingUrlTab = (): boolean => {
        if (S.quanta.initialTab) {
            if (S.quanta.initialTab === C.TAB_FEED && S.quanta.tagSearch) {
                TrendingView.searchWord(null, "#" + S.quanta.tagSearch);
                S.quanta.tagSearch = null;
            }
            else {
                S.tabUtil.selectTab(S.quanta.initialTab);
                if (S.quanta.initialTab === C.TAB_DOCUMENT && S.quanta.initialNodeId) {
                    S.nav.openDocumentView(null, S.quanta.initialNodeId);
                    S.quanta.initialNodeId = null;
                }
            }
            S.quanta.initialTab = null;
            return true;
        }
        return false;
    }

    refreshLogin = async () => {
        const loginState: string = await S.localDB.getVal(C.LOCALDB_LOGIN_STATE);

        /* if we have *known* state as logged out, then do nothing here */
        if (loginState && loginState === "0") {
            if (!this.usingUrlTab()) {
                S.util.loadAnonPageHome();
            }
            return;
        }

        const usr = await S.localDB.getVal(C.LOCALDB_LOGIN_USR);
        const pwd = await S.localDB.getVal(C.LOCALDB_LOGIN_PWD);
        const usingCredentials: boolean = usr && pwd;

        /*
         * empyt credentials causes server to try to log in with any active session credentials.
         */
        const callUsr: string = usr || "";
        const callPwd: string = pwd || "";

        if (!callUsr) {
            if (!this.usingUrlTab()) {
                await this.anonInitialRender();
            }
        } else {
            try {
                if (S.crypto.avail) {
                    await S.crypto.initKeys(callUsr, false, false, false, "all");
                }
                const res = await S.rpcUtil.rpc<J.LoginRequest, J.LoginResponse>("login", {
                    userName: callUsr,
                    password: callPwd,
                    tzOffset: new Date().getTimezoneOffset(),
                    dst: S.util.daylightSavingsTime,
                    sigKey: S.quanta.sigKey,
                    asymEncKey: S.quanta.asymEncKey
                }, false, true);
                S.quanta.authToken = res.authToken;

                if (res && !res.success) {
                    await S.localDB.setVal(C.LOCALDB_LOGIN_STATE, "0");
                    await S.localDB.setVal(C.LOCALDB_LOGIN_STATE, "0", J.PrincipalName.ANON);
                }

                if (usingCredentials) {
                    // Note: If user entered wrong case-sentitivity string on login dialog they can still login
                    // but this res.userName however will have the correct name (case-sensitive) here now.
                    await this.loginResponse(res, res.userProfile.userName, callPwd, false);
                } else {
                    if (res.success) {
                        S.util.setStateVarsUsingLoginResponse(res);
                    }

                    if (!this.usingUrlTab()) {
                        await this.anonInitialRender();
                    }
                }
            }
            catch (e) {
                await S.localDB.setVal(C.LOCALDB_LOGIN_STATE, "0");
                await S.localDB.setVal(C.LOCALDB_LOGIN_STATE, "0", J.PrincipalName.ANON);
                await this.anonInitialRender();
            }
        }
    }

    anonInitialRender = async () => {
        if (S.quanta.initialTab) {
            S.tabUtil.selectTab(S.quanta.initialTab);
            S.quanta.initialTab = null;
        }
        else {
            await S.util.loadAnonPageHome();
        }
    }

    logout = async (updateLocalDb: any) => {
        if (getAs().isAnonUser) {
            return;
        }

        /* Remove warning dialog to ask user about leaving the page */
        window.onbeforeunload = null;

        if (updateLocalDb) {
            await S.localDB.setVal(C.LOCALDB_LOGIN_STATE, "0");
            await S.localDB.setVal(C.LOCALDB_LOGIN_STATE, "0", J.PrincipalName.ANON);
        }

        S.quanta.loggingOut = true;
        try {
            await S.rpcUtil.rpc<J.LogoutRequest, J.LogoutResponse>("logout");
        }
        finally {
            this.logoutResponse();
        }
    }

    logoutResponse = () => {
        S.push.close();
        S.quanta.authToken = null;
        S.quanta.userName = null;
        window.location.href = window.location.origin;
    }

    deleteAllUserLocalDbEntries = (): Promise<any> => {
        return Promise.all([
            S.localDB.setVal(C.LOCALDB_LOGIN_PWD, null),
            S.localDB.setVal(C.LOCALDB_LOGIN_STATE, "0"),
            S.localDB.setVal(C.LOCALDB_LOGIN_STATE, "0", J.PrincipalName.ANON)
        ]);
    }

    loginResponse = async (res: J.LoginResponse, usr: string, pwd: string, calledFromLoginDlg: boolean) => {
        if (S.util.checkSuccess("Login", res)) {

            // if login was successful and we're an authenticated user
            if (usr !== J.PrincipalName.ANON) {

                await promiseDispatch("unknownPubKeys", s => {
                    s.unknownPubEncKey = res.unknownPubEncKey;
                    s.unknownPubSigKey = res.unknownPubSigKey;
                });

                S.localDB.userName = usr;
                if (usr) {
                    await S.localDB.setVal(C.LOCALDB_LOGIN_USR, usr);
                    // set this user for the 'anon' case also meaning it'll be default when user it not logged in
                    await S.localDB.setVal(C.LOCALDB_LOGIN_USR, usr, J.PrincipalName.ANON);
                }

                if (pwd) {
                    await S.localDB.setVal(C.LOCALDB_LOGIN_PWD, pwd);
                    // set this pwd for the 'anon' case also meaning it'll be default when user it not logged in
                    await S.localDB.setVal(C.LOCALDB_LOGIN_PWD, pwd, J.PrincipalName.ANON);
                }
                await S.localDB.setVal(C.LOCALDB_LOGIN_STATE, "1");
                await S.localDB.setVal(C.LOCALDB_LOGIN_STATE, "1", J.PrincipalName.ANON);

                S.quanta.userName = usr;
                console.log("Logged in as: " + usr);

                // todo-1: technically this delay is a bit of a hack because we really need a way to be SURE
                // the main app layout has already loaded before we even try to resume editing.
                setTimeout(async () => {
                    if (usr !== J.PrincipalName.ADMIN) {
                        await this.checkMessages();
                    }
                    await S.util.loadBookmarks();
                    await S.util.resumeEditingOfAbandoned();
                }, 500);
            }

            await S.util.setStateVarsUsingLoginResponse(res);

            /* set ID to be the page we want to show user right after login */
            let id: string = null;
            let childId: string = null;
            let renderParentIfLeaf = true;

            /* if we know the server already failed to get the content requested on the url then
            default to main tab (tree) and set it up to display an error */
            if (S.quanta.configRes.urlIdFailMsg) {
                await promiseDispatch("setAccessFailed", s => {
                    s.activeTab = S.quanta.activeTab = C.TAB_MAIN;
                });
                return;
            }

            if (this.usingUrlTab()) {
                return;
            }

            // we may have just processed a dispatch so we need to get the current state now.
            const ast = getAs();

            if (S.quanta.initialNodeId) {
                id = S.quanta.initialNodeId;
                S.quanta.initialNodeId = null;
                if (id && id.startsWith("~")) {
                    renderParentIfLeaf = false;
                }
            } //
            else {
                const lastNode = await S.localDB.getVal(C.LOCALDB_LAST_PARENT_NODEID);

                if (lastNode) {
                    id = lastNode;
                    childId = await S.localDB.getVal(C.LOCALDB_LAST_CHILD_NODEID);
                } else {
                    id = ast.userProfile?.userNodeId;
                }
            }

            await S.view.refreshTree({
                nodeId: id,
                zeroOffset: true,
                renderParentIfLeaf,
                highlightId: childId,
                forceIPFSRefresh: false,
                scrollToTop: false,
                allowScroll: true,
                setTab: true,
                forceRenderParent: false
            });
        } else {
            console.log("LocalDb login failed.");

            // if we tried a login and it wasn't from a login dialog then just blow away the login state
            // so that any kind of page refresh is guaranteed to just show login dialog and not try to login
            S.localDB.setVal(C.LOCALDB_LOGIN_STATE, "0");
            S.localDB.setVal(C.LOCALDB_LOGIN_STATE, "0", J.PrincipalName.ANON);

            if (!calledFromLoginDlg) {
                this.userLogin();
            }
        }
    }

    checkMessages = async () => {
        const res = await S.rpcUtil.rpc<J.CheckMessagesRequest, J.CheckMessagesResponse>("checkMessages", null, true);
        if (res) {
            dispatch("SetNewMessageCount", s => {
                s.newMessageCount = res.numNew;
            });
        }
    }

    queryUserProfile = async (userId: string) => {
        const res = await S.rpcUtil.rpc<J.GetUserProfileRequest, J.GetUserProfileResponse>("getUserProfile", {
            userId
        });

        if (res?.userProfile) {
            await promiseDispatch("SetUserProfile", s => {
                s.userProfile = res.userProfile;
            });
        }
    }

    userLogin = async () => {
        new LoginDlg().open();
    }

    userLogout = () => {
        this.logout(true);
    }

    userSignup = () => {
        new SignupDlg().open();
    }

    showUsersList = (node: J.NodeInfo) => {
        const friendsDlg = new FriendsDlg("People Associated with Node", node.id, true);
        friendsDlg.open();
    }
}
