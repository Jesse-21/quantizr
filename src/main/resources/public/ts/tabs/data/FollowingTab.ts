import { AppState } from "../../AppState";
import { Div } from "../../comp/core/Div";
import { OpenGraphPanel } from "../../comp/OpenGraphPanel";
import { Constants as C } from "../../Constants";
import { FollowingRSInfo } from "../../FollowingRSInfo";
import { TabIntf } from "../../intf/TabIntf";
import * as J from "../../JavaIntf";
import { S } from "../../Singletons";
import { FollowingResultSetView } from "../FollowingResultSetView";

export class FollowingTab implements TabIntf<FollowingRSInfo> {
    name = "Following";
    tooltip = "List of people the person is following";
    id = C.TAB_FOLLOWING;
    props = new FollowingRSInfo();
    scrollPos = 0;
    openGraphComps: OpenGraphPanel[] = [];

    static inst: FollowingTab = null;
    constructor() {
        FollowingTab.inst = this;
    }

    isVisible = (state: AppState) => S.tabUtil.resultSetHasData(C.TAB_FOLLOWING);
    constructView = (data: TabIntf) => new FollowingResultSetView<FollowingRSInfo>(data);
    getTabSubOptions = (state: AppState): Div => { return null; };

    findNode = (state: AppState, nodeId: string): J.NodeInfo => {
        return this.props.results?.find(n => n.id === nodeId);
    }

    nodeDeleted = (state: AppState, nodeId: string): void => {
        this.props.results = this.props.results?.filter(n => nodeId !== n.id);
    }

    replaceNode = (state: AppState, newNode: J.NodeInfo): void => {
        this.props.results = this.props.results?.map(n => {
            return n.id === newNode.id ? newNode : n;
        });
    }
}