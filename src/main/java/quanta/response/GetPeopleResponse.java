package quanta.response;

import java.util.LinkedList;
import java.util.List;

import quanta.response.base.ResponseBase;

public class GetPeopleResponse extends ResponseBase {

    private FriendInfo nodeOwner;
    private List<FriendInfo> people;
    private LinkedList<String> friendHashTags;

    public FriendInfo getNodeOwner() {
        return nodeOwner;
    }

    public void setNodeOwner(FriendInfo nodeOwner) {
        this.nodeOwner = nodeOwner;
    }

    public List<FriendInfo> getPeople() {
        return people;
    }

    public void setPeople(List<FriendInfo> people) {
        this.people = people;
    }

    public LinkedList<String> getFriendHashTags() {
        return friendHashTags;
    }

    public void setFriendHashTags(LinkedList<String> friendHashTags) {
        this.friendHashTags = friendHashTags;
    }
}
