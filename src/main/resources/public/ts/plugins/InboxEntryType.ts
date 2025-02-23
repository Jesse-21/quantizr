import { NodeActionType } from "../intf/TypeIntf";
import * as J from "../JavaIntf";
import { TypeBase } from "./base/TypeBase";

export class InboxEntryType extends TypeBase {
    constructor() {
        super(J.NodeType.INBOX_ENTRY, "Notification", "fa-envelope", false);
    }

    allowAction(action: NodeActionType, node: J.NodeInfo): boolean {
        switch (action) {
            case NodeActionType.delete:
                return true;
            default:
                return false;
        }
    }

    getAllowPropertyAdd(): boolean {
        return false;
    }

    getAllowContentEdit(): boolean {
        return false;
    }

    allowPropertyEdit(propName: string): boolean {
        return false;
    }
}
