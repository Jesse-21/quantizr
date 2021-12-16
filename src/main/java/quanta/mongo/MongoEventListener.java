package quanta.mongo;

import java.util.Calendar;
import java.util.Date;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.mapping.event.AbstractMongoEventListener;
import org.springframework.data.mongodb.core.mapping.event.AfterConvertEvent;
import org.springframework.data.mongodb.core.mapping.event.AfterLoadEvent;
import org.springframework.data.mongodb.core.mapping.event.AfterSaveEvent;
import org.springframework.data.mongodb.core.mapping.event.BeforeDeleteEvent;
import org.springframework.data.mongodb.core.mapping.event.BeforeSaveEvent;
import quanta.actpub.ActPubService;
import quanta.config.NodeName;
import quanta.mongo.model.SubNode;
import quanta.util.SubNodeUtil;
import quanta.util.ThreadLocals;
import quanta.util.XString;
import static quanta.util.Util.*;

/**
 * Listener that MongoDB driver hooks into so we can inject processing into various phases of the
 * persistence (reads/writes) of the MongoDB objects.
 */
public class MongoEventListener extends AbstractMongoEventListener<SubNode> {
	private static final Logger log = LoggerFactory.getLogger(MongoEventListener.class);
	private static final boolean verbose = false;

	@Autowired
	private MongoTemplate ops;

	@Autowired
	private MongoRead read;

	@Autowired
	private MongoAuth auth;

	@Autowired
	private ActPubService actPub;

	@Autowired
	private MongoUtil mongoUtil;

	@Autowired
	private SubNodeUtil snUtil;

	/**
	 * What we are doing in this method is assigning the ObjectId ourselves, because our path must
	 * include this id at the very end, since the path itself must be unique. So we assign this prior to
	 * persisting so that when we persist everything is perfect.
	 * 
	 * WARNING: updating properties on 'node' in here has NO EFFECT. Always update dbObj only!
	 */
	@Override
	public void onBeforeSave(BeforeSaveEvent<SubNode> event) {
		SubNode node = event.getSource();
		log.trace("MDB save: " + node.getPath() + " thread: " + Thread.currentThread().getName());

		Document dbObj = event.getDocument();
		ObjectId id = node.getId();
		boolean isNew = false;

		/*
		 * Note: There's a special case in MongoApi#createUser where the new User root node ID is assigned
		 * there, along with setting that on the owner property so we can do one save and have both updated
		 */
		if (no(id)) {
			id = new ObjectId();
			node.setId(id);
			isNew = true;
			// log.debug("New Node ID generated: " + id);
		}
		dbObj.put(SubNode.ID, id);

		if (no(node.getOrdinal())) {
			node.setOrdinal(0L);
			dbObj.put(SubNode.ORDINAL, 0L);
		}

		// log.debug("onBeforeSave: ID: " + node.getIdStr());

		// DO NOT DELETE
		/*
		 * If we ever add a unique-index for "Name" (not currently the case), then we'd need something like
		 * this to be sure each node WOULD have a unique name.
		 */
		// if (StringUtils.isEmpty(node.getName())) {
		// node.setName(id.toHexString())
		// }

		/* if no owner is assigned... */
		if (no(node.getOwner())) {
			/*
			 * if we are saving the root node, we make it be the owner of itself. This is also the admin owner,
			 * and we only allow this to run during initialiation when the server may be creating the database,
			 * and is not yet processing user requests
			 */
			if (node.getPath().equals("/" + NodeName.ROOT) && !MongoRepository.fullInit) {
				dbObj.put(SubNode.OWNER, id);
				node.setOwner(id);
			} else {
				if (ok(auth.getAdminSession())) {
					ObjectId ownerId = auth.getAdminSession().getUserNodeId();
					dbObj.put(SubNode.OWNER, ownerId);
					node.setOwner(ownerId);
					log.debug("Assigning admin as owner of node that had no owner (on save): " + id);
				}
			}
		}

		if (ThreadLocals.getParentCheckEnabled()) {
			read.checkParentExists(null, node);
		}

		Date now = null;

		/* If no create/mod time has been set, then set it */
		if (no(node.getCreateTime())) {
			if (no(now)) {
				now = Calendar.getInstance().getTime();
			}
			dbObj.put(SubNode.CREATE_TIME, now);
			node.setCreateTime(now);
		}

		if (no(node.getModifyTime())) {
			if (no(now)) {
				now = Calendar.getInstance().getTime();
			}
			dbObj.put(SubNode.MODIFY_TIME, now);
			node.setModifyTime(now);
		}

		/*
		 * New nodes can be given a path where they will allow the ID to play the role of the leaf 'name'
		 * part of the path
		 */
		if (node.getPath().endsWith("/?")) {
			String path = mongoUtil.findAvailablePath(XString.removeLastChar(node.getPath()));
			dbObj.put(SubNode.PATH, path);
			node.setPath(path);
		}

		saveAuthByThread(node, isNew);

		/* Node name not allowed to contain : or ~ */
		String nodeName = node.getName();
		if (ok(nodeName)) {
			nodeName = nodeName.replace(":", "-");
			nodeName = nodeName.replace("~", "-");
			nodeName = nodeName.replace("/", "-");

			// Warning: this is not a redundant null check. Some code in this block CAN set
			// to null.
			if (ok(nodeName)) {
				dbObj.put(SubNode.NAME, nodeName);
				node.setName(nodeName);
			}
		}

		snUtil.removeDefaultProps(node);

		if (ok(node.getAc())) {
			/*
			 * we need to ensure that we never save an empty Acl, but null instead, because some parts of the
			 * code assume that if the AC is non-null then there ARE some shares on the node.
			 * 
			 * This 'fix' only started being necessary I think once I added the safeGetAc, and that check ends
			 * up causing the AC to contain an empty object sometimes
			 */
			if (node.getAc().size() == 0) {
				node.setAc(null);
				dbObj.put(SubNode.AC, null);
			}
			// Remove any share to self because that never makes sense
			else {
				if (ok(node.getOwner())) {
					if (ok(node.getAc().remove(node.getOwner().toHexString()))) {
						dbObj.put(SubNode.AC, node.getAc());
					}
				}
			}
		}

		ThreadLocals.clean(node);
	}

	@Override
	public void onAfterSave(AfterSaveEvent<SubNode> event) {
		SubNode node = event.getSource();
		if (ok(node)) {
			ThreadLocals.cacheNode(node);
		}
	}

	@Override
	public void onAfterLoad(AfterLoadEvent<SubNode> event) {
		// Document dbObj = event.getDocument();
		// log.debug("onAfterLoad:
		// id="+dbObj.getObjectId(SubNode.FIELD_ID).toHexString());
	}

	@Override
	public void onAfterConvert(AfterConvertEvent<SubNode> event) {
		SubNode node = event.getSource();
		if (no(node.getOwner())) {
			if (ok(auth.getAdminSession())) {
				ObjectId ownerId = auth.getAdminSession().getUserNodeId();
				node.setOwner(ownerId);
				log.debug("Assigning admin as owner of node that had no owner (on load): " + node.getIdStr());
			}
		}

		ThreadLocals.cacheNode(node);
	}

	@Override
	public void onBeforeDelete(BeforeDeleteEvent<SubNode> event) {
		Document doc = event.getDocument();

		if (ok(doc)) {
			Object id = doc.get("_id");
			if (id instanceof ObjectId) {
				SubNode node = ops.findById(id, SubNode.class);
				if (ok(node)) {
					log.trace("MDB del: " + node.getPath());
					auth.ownerAuthByThread(node);
					ThreadLocals.clean(node);
				}
				// because nodes can be orphaned, we clear the entire cache any time any nodes are deleted
				ThreadLocals.clearCachedNodes();
				actPub.deleteNodeNotify((ObjectId) id);
			}
		}
	}

	/* To save a node you must own the node and have WRITE access to it's parent */
	public void saveAuthByThread(SubNode node, boolean isNew) {
		// during server init no auth is required.
		if (!MongoRepository.fullInit) {
			return;
		}
		if (verbose)
			log.trace("saveAuth in MongoListener");

		MongoSession ms = ThreadLocals.getMongoSession();
		if (ok(ms)) {
			if (ms.isAdmin())
				return;

			// Must have write privileges to this node or one of it's parents.
			auth.ownerAuthByThread(node);

			// only if this is creating a new node do we need to chech that the parent will allow it
			if (isNew) {
				SubNode parent = read.getParent(ms, node);
				if (no(parent))
					throw new RuntimeException("unable to get node parent: " + node.getParentPath());

				auth.authForChildNodeCreate(ms, parent);
			}
		}
	}
}