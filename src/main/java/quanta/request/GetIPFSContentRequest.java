package quanta.request;

import quanta.request.base.RequestBase;

public class GetIPFSContentRequest extends RequestBase {

    // rename this to 'mfsPath'
    private String id;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }
}