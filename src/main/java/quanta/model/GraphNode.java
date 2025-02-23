package quanta.model;

import static quanta.util.Util.no;
import static quanta.util.Util.ok;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnore;
import quanta.model.client.NodeLink;
import org.springframework.data.annotation.Transient;

public class GraphNode {
    private String id;
    private int level;
    private boolean highlight;
    private String name;
    private String path;
    private List<GraphNode> children;
    private HashSet<String> childIds;
    private HashMap<String, NodeLink> links;

    public GraphNode() {}

    public GraphNode(String id, String name, String path, int level, boolean highlight, HashMap<String, NodeLink> links) {
        this.id = id;
        this.name = name;
        this.path = path;
        this.level = level;
        this.highlight = highlight;
        this.links = links;
    }

    public void addChild(GraphNode child) {
        if (ok(childIds) && childIds.contains(child.getId()))
            return;

        if (no(children)) {
            children = new LinkedList<>();
        }
        children.add(child);
        if (no(childIds)) {
            childIds = new HashSet<>();
        }
        childIds.add(child.getId());
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<GraphNode> getChildren() {
        return children;
    }

    public void setChildren(List<GraphNode> children) {
        this.children = children;
    }

    @Transient
    @JsonIgnore
    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    @Transient
    @JsonIgnore
    public HashSet<String> getChildIds() {
        return childIds;
    }

    public void setChildIds(HashSet<String> childIds) {
        this.childIds = childIds;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public int getLevel() {
        return level;
    }

    public void setLevel(int level) {
        this.level = level;
    }

    public boolean isHighlight() {
        return highlight;
    }

    public void setHighlight(boolean highlight) {
        this.highlight = highlight;
    }

    public HashMap<String, NodeLink> getLinks() {
        return links;
    }

    public void setLinks(HashMap<String, NodeLink> links) {
        this.links = links;
    }
}
