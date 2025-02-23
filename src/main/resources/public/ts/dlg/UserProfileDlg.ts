import { dispatch, getAs } from "../AppContext";
import { ScrollPos } from "../comp/base/Comp";
import { CompIntf } from "../comp/base/CompIntf";
import { Button } from "../comp/core/Button";
import { ButtonBar } from "../comp/core/ButtonBar";
import { Checkbox } from "../comp/core/Checkbox";
import { Div } from "../comp/core/Div";
import { FlexRowLayout } from "../comp/core/FlexRowLayout";
import { Heading } from "../comp/core/Heading";
import { Html } from "../comp/core/Html";
import { Img } from "../comp/core/Img";
import { Label } from "../comp/core/Label";
import { Span } from "../comp/core/Span";
import { TextArea } from "../comp/core/TextArea";
import { TextField } from "../comp/core/TextField";
import { DialogBase } from "../DialogBase";
import * as J from "../JavaIntf";
import { NodeType } from "../JavaIntf";
import { S } from "../Singletons";
import { Validator } from "../Validator";
import { UploadFromFileDropzoneDlg } from "./UploadFromFileDropzoneDlg";

interface LS { // Local State
    userProfile: J.UserProfile;
}

export class UserProfileDlg extends DialogBase {
    readOnly: boolean;
    bioState: Validator = new Validator();
    displayNameState: Validator = new Validator();
    textScrollPos = new ScrollPos();

    /* If no userNodeId is specified this dialog defaults to the current logged in user, or else will be
    some other user, and this dialog should be readOnly */
    constructor(private userNodeId: string) {
        super("User Profile", "app-modal-content");
        const ast = getAs();
        userNodeId = userNodeId || ast.userProfile.userNodeId;
        this.readOnly = !ast.userProfile || ast.userProfile.userNodeId !== userNodeId;
        this.mergeState<LS>({ userProfile: null });
    }

    getTitleText(): string {
        return this.getUserName();
    }

    getUserName = (): string => {
        const state: any = this.getState<LS>();
        if (!state.userProfile) return "";
        let userName = state.userProfile.userName;
        if (userName.indexOf("@") === -1) {
            userName = userName + "@" + window.location.hostname;
        }
        return userName;
    }

    renderDlg(): CompIntf[] {
        const state = this.getState<LS>();
        const ast = getAs();
        if (!state.userProfile) {
            return [new Label("Loading...")];
        }

        const profileHeaderImg = this.makeProfileHeaderImg();
        const profileImg = this.makeProfileImg(!!profileHeaderImg);
        const localUser = S.util.isLocalUserName(state.userProfile.userName);
        let web3Div: Div = null;
        const web3Enabled = ast.allowedFeatures && ast.allowedFeatures.indexOf("web3") !== -1;

        if (ast.config.ipfsEnabled && web3Enabled) {
            const web3Comps: CompIntf[] = [];

            if (state.userProfile.didIPNS) {
                web3Comps.push(new Div("Identity: " + "/ipns/" + state.userProfile.didIPNS, {
                    title: "Decentralized Identity (DID) IPNS Name",
                    className: "marginTop clickable",
                    onClick: () => {
                        const link = "https://ipfs.io/ipns/" + state.userProfile.didIPNS;
                        S.util.copyToClipboard(link);
                        S.util.flashMessage("Copied link to Clipboard", "Clipboard", true);
                    }
                }));
            }

            if (!this.readOnly) {
                web3Comps.push(new Checkbox("Web3 File System", null, {
                    setValue: (checked: boolean) => {
                        const state = this.getState<LS>();
                        state.userProfile.mfsEnable = checked;
                        this.mergeState<LS>({ userProfile: state.userProfile });
                    },
                    getValue: (): boolean => this.getState<LS>().userProfile.mfsEnable
                }));
            }

            web3Div = new Div(null, null, web3Comps);
        }

        const children = [
            new Div(null, null, [
                profileHeaderImg ? new Div(null, null, [
                    new Div(null, null, [
                        profileHeaderImg
                    ])
                ]) : null,

                new FlexRowLayout([
                    profileImg,
                    new Div(null, { className: "userDisplayName" }, [
                        new Div(null, { className: "marginLeft" }, [
                            this.readOnly
                                ? new Heading(4, state.userProfile.displayName || "")
                                : new TextField({ label: "Display Name", inputClass: "displayNameTextField", val: this.displayNameState })
                        ]),
                        new Div(null, { className: "float-end" }, [
                            state.userProfile.blocked ? new Span("You Blocked", {
                                className: "blockingText",
                                onClick: this.unblockUser,
                                title: "Click to Unblock user"
                            }) : null,
                            state.userProfile.following ? new Span("You Follow", {
                                className: "followingText",
                                onClick: this.deleteFriend,
                                title: "Click to Unfollow user"
                            }) : null,

                            state.userProfile.followerCount > 0 ? new Span(state.userProfile.followerCount + " followers", {
                                onClick: () => {
                                    if (state.userProfile.followerCount) {
                                        this.close();
                                        if (localUser) {
                                            S.srch.showFollowers(0, state.userProfile.userName);
                                        }
                                        else {
                                            window.open(state.userProfile.actorUrl || state.userProfile.actorId, "_blank");
                                        }
                                    }
                                },
                                className: "followCount"
                            }) : null,

                            state.userProfile.followingCount > 0 ? new Span("following " + state.userProfile.followingCount, {
                                onClick: () => {
                                    if (state.userProfile.followingCount) {
                                        this.close();
                                        if (localUser) {
                                            S.srch.showFollowing(0, state.userProfile.userName);
                                        }
                                        else {
                                            window.open(state.userProfile.actorUrl || state.userProfile.actorId, "_blank");
                                        }

                                        // It would be 'inconsistent' to just jump to the FRIEND_LIST? if this user is looking
                                        // at their own user profile dialog? There's also even the Friend Picker dialog too!
                                        // S.nav.openContentNode("~" + J.NodeType.FRIEND_LIST);
                                    }
                                },
                                className: "followCount"
                            }) : null
                        ])
                    ])
                ], "avatarAndNamePanel"),

                this.readOnly
                    ? new Html(S.util.markdown(state.userProfile.userBio) || "", { className: "bioPanel" })
                    : new TextArea("About Me", {
                        rows: 5
                    }, this.bioState, null, false, this.textScrollPos),

                web3Div,

                new ButtonBar([
                    getAs().isAnonUser || this.readOnly ? null : new Button("Save", this.save, null, "btn-primary"),
                    (getAs().isAnonUser || this.readOnly || !ast.config.ipfsEnabled || !web3Enabled) ? null : new Button("Publish Identity", this.publish, {
                        title: "Publish Identity to IPFS/IPNS (Decentralized Identity, DID)"
                    }),

                    // only local users might have set their 'home' node (named a node 'home')
                    localUser && state.userProfile.homeNodeId ? new Button("Home", () => this.openUserHomePage(state, "home")) : null, //

                    // but all users we know of will have a posts node simply from having their posts imported
                    new Button("Posts", () => {
                        if (this.currentlyEditingWarning()) return;
                        this.openUserHomePage(state, "posts");
                    }), //

                    !ast.isAnonUser && this.readOnly && state.userProfile.userName !== getAs().userName
                        ? new Button("Message", this.sendMessage, { title: "Compose a new message to " + state.userProfile.userName }) : null,

                    !ast.isAnonUser && this.readOnly && state.userProfile.userName !== getAs().userName
                        ? new Button("Interactions", this.previousMessages, { title: "Show interactions between you and " + state.userProfile.userName }) : null,

                    !ast.isAnonUser
                        ? new Button("Mentions", () => this.searchMentions(this.getUserName()), { title: "Find all Public Mentions of this person" }) : null,

                    // only show editFriend node if we're NOT currently editing.
                    !ast.editNode && !ast.isAnonUser && state.userProfile.following && this.readOnly && state.userProfile.userName !== getAs().userName
                        ? new Button("Friend Settings", this.editFriendNode) : null,

                    !ast.isAnonUser && !state.userProfile.following && this.readOnly && state.userProfile.userName !== getAs().userName
                        ? new Button("Follow", this.addFriend) : null,

                    !ast.isAnonUser && !state.userProfile.blocked && this.readOnly && state.userProfile.userName !== getAs().userName
                        ? new Button("Block", this.blockUser) : null,

                    ast.isAdminUser ? new Button("Read Outbox", () => S.view.runServerCommand("readOutbox", state.userProfile.userName, "Read User Outbox: " + state.userProfile.userName, "")) : null,

                    state.userProfile.actorUrl || state.userProfile.actorId ? new Button("User Page", () => {
                        window.open(state.userProfile.actorUrl || state.userProfile.actorId, "_blank");
                    }) : null,

                    new ButtonBar([
                        !this.readOnly ? new Button("Logout", S.user.userLogout) : null,
                        new Button(this.readOnly ? "Close" : "Cancel", this.close, null, "btn-secondary")
                    ], "float-end")
                ], "marginTop")
            ])
        ];

        return children;
    }

    deleteFriend = async () => {
        await S.rpcUtil.rpc<J.DeleteFriendRequest, J.DeleteFriendResponse>("deleteFriend", {
            userNodeId: this.userNodeId
        });
        this.reload(this.userNodeId);
    }

    unblockUser = async () => {
        await S.rpcUtil.rpc<J.DeleteFriendRequest, J.DeleteFriendResponse>("unblockUser", {
            userNodeId: this.userNodeId
        });
        this.reload(this.userNodeId);
    }

    /**
     * NOTE: There's two different URL formats here because there's two different ways to access
     * a named node (which are: via url, or via a parameter on the url)
     */
    openUserHomePage = (state: any, nodeName: string) => {
        // let url = window.location.origin + "/u/" + state.userProfile.userName + "/" + nodeName;
        // window.open(url, "_blank");
        this.close();
        setTimeout(() => S.nav.openContentNode(":" + state.userProfile.userName + ":" + nodeName), 250);
    }

    reload = async (userNodeId: string) => {
        const res = await S.rpcUtil.rpc<J.GetUserProfileRequest, J.GetUserProfileResponse>("getUserProfile", {
            userId: userNodeId
        });

        // console.log("UserProfile Response: " + S.util.prettyPrint(res));
        if (res?.userProfile) {
            this.bioState.setValue(res.userProfile.userBio);
            this.displayNameState.setValue(res.userProfile.displayName);
            this.mergeState<LS>({
                userProfile: res.userProfile
            });
        }
    }

    save = async () => {
        const state = this.getState<LS>();
        const res = await S.rpcUtil.rpc<J.SaveUserProfileRequest, J.SaveUserProfileResponse>("saveUserProfile", {
            userName: null,
            userTags: getAs().userProfile.userTags,
            userBio: this.bioState.getValue(),
            displayName: this.displayNameState.getValue(),
            publish: false,
            mfsEnable: state.userProfile.mfsEnable
        });
        this.saveResponse(res);
    }

    publish = async () => {
        const state = this.getState<LS>();
        const res = await S.rpcUtil.rpc<J.SaveUserProfileRequest, J.SaveUserProfileResponse>("saveUserProfile", {
            userName: null,
            userTags: getAs().userProfile.userTags,
            userBio: this.bioState.getValue(),
            displayName: this.displayNameState.getValue(),
            publish: true,
            mfsEnable: state.userProfile.mfsEnable
        });
        this.saveResponse(res);
    }

    addFriend = async () => {
        const state: any = this.getState<LS>();
        const res = await S.rpcUtil.rpc<J.AddFriendRequest, J.AddFriendResponse>("addFriend", {
            userName: state.userProfile.userName
        });

        if (res.success) {
            state.userProfile.following = true;
            state.userProfile.blocked = false;
            this.mergeState<LS>({
                userProfile: state.userProfile
            });
        }
    }

    editFriendNode = async () => {
        if (this.currentlyEditingWarning()) return;
        S.edit.runEditNode(null, this.userNodeId, true, false, true, null, true);
    }

    currentlyEditingWarning = (): boolean => {
        const ast = getAs();
        if (ast.editNode) {
            S.util.showMessage("You must first finish editing the node.", "Warning");
            return true;
        }
        return false;
    }

    sendMessage = () => {
        if (this.currentlyEditingWarning()) return;
        this.close();
        setTimeout(() => {
            S.edit.addNode(null, NodeType.COMMENT, false, null, this.userNodeId, null, null, false);
        }, 10);
    }

    previousMessages = () => {
        if (this.currentlyEditingWarning()) return;
        this.close();
        setTimeout(() => {
            const state: any = this.getState<LS>();
            S.nav.messagesFromMeToUser(state.userProfile.userName);
        }, 10);
    }

    searchMentions = (fullUserName: string) => {
        if (this.currentlyEditingWarning()) return;
        this.close();
        setTimeout(() => { S.nav.messagesFindMentions(fullUserName); }, 10);
    }

    blockUser = async () => {
        const state: any = this.getState<LS>();
        const res = await S.rpcUtil.rpc<J.BlockUserRequest, J.BlockUserResponse>("blockUser", {
            userName: state.userProfile.userName
        });

        if (res.success) {
            state.userProfile.blocked = true;
            state.userProfile.following = false;
            this.mergeState<LS>({
                userProfile: state.userProfile
            });
        }
    }

    super_close = this.close;
    close = () => {
        this.super_close();
        if (!this.readOnly) {
            S.user.queryUserProfile(this.userNodeId);
        }
    }

    saveResponse = (res: J.SaveUserPreferencesResponse) => {
        this.close();
        dispatch("SaveUserPerferences", s => {
            s.displayName = this.displayNameState.getValue();
        });
    }

    makeProfileImg(hasHeaderImg: boolean): CompIntf {
        let src: string = null;
        const state: any = this.getState<LS>();

        // if ActivityPub icon exists, we know that's the one to use.
        if (state.userProfile.apIconUrl) {
            src = state.userProfile.apIconUrl;
        }
        else {
            const avatarVer = state.userProfile.avatarVer;
            src = S.render.getAvatarImgUrl(state.userProfile.userNodeId, avatarVer);
        }

        const onClick = async () => {
            if (this.readOnly) return;

            const dlg = new UploadFromFileDropzoneDlg(state.userProfile.userNodeId, J.Constant.ATTACHMENT_PRIMARY, false, null, false, false, async () => {
                const res = await S.rpcUtil.rpc<J.GetUserProfileRequest, J.GetUserProfileResponse>("getUserProfile", {
                    userId: state.userProfile.userNodeId
                });

                if (res?.userProfile) {
                    state.userProfile.avatarVer = res.userProfile.avatarVer;
                    state.userProfile.userNodeId = res.userProfile.userNodeId;
                    this.mergeState<LS>({
                        userProfile: state.userProfile
                    });
                }
            });
            dlg.open();
        };

        if (src) {
            const att: any = {
                className: "userProfileDlgAvatar",
                src,
                onClick
            };
            if (!this.readOnly) {
                att.title = "Click to upload Avatar Image";
            }

            // Note: we DO have the image width/height set on the node object (node.width, node.hight) but we don't need it for anything currently
            return new Img(att);
        }
        else {
            if (this.readOnly) {
                return new Div("No avatar image", {
                    className: "userProfileDlgAvatarNone"
                });

            }
            return new Div("Click to upload Avatar Image", {
                className: "userProfileDlgAvatarNone",
                onClick
            });
        }
    }

    makeProfileHeaderImg(): CompIntf {
        let src: string = null;
        const state: any = this.getState<LS>();

        if (state.userProfile.apImageUrl) {
            src = state.userProfile.apImageUrl;
        }
        else {
            const headerImageVer = state.userProfile.headerImageVer;
            src = S.render.getProfileHeaderImgUrl(state.userProfile.userNodeId, headerImageVer);
        }

        const onClick = () => {
            if (this.readOnly) return;
            const dlg = new UploadFromFileDropzoneDlg(state.userProfile.userNodeId, J.Constant.ATTACHMENT_HEADER, false, null, false, false,
                async () => {
                    const res = await S.rpcUtil.rpc<J.GetUserProfileRequest, J.GetUserProfileResponse>("getUserProfile", {
                        userId: state.userProfile.userNodeId
                    });

                    if (res?.userProfile) {
                        state.userProfile.headerImageVer = res.userProfile.headerImageVer;
                        state.userProfile.userNodeId = res.userProfile.userNodeId;
                        this.mergeState<LS>({
                            userProfile: state.userProfile
                        });
                    }
                });
            dlg.open();
        };

        if (src) {
            const att: any = {
                className: "userProfileDlgHeader",
                src,
                onClick
            };
            if (!this.readOnly) {
                att.title = "Click to upload Header Image";
            }

            // Note: we DO have the image width/height set on the node object (node.width, node.hight) but we don't need it for anything currently
            return new Img(att);
        }
        else {
            return this.readOnly ? null : new Div("Click to upload Header Image", {
                className: "userProfileDlgHeaderNone",
                onClick
            });
        }
    }

    async preLoad(): Promise<void> {
        await S.rpcUtil.rpc<J.GetUserAccountInfoRequest, J.GetUserAccountInfoResponse>("getUserAccountInfo");
        await this.reload(this.userNodeId);
    }
}
