package quanta.service;

import static quanta.util.Util.no;
import static quanta.util.Util.ok;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import quanta.actpub.APConst;
import quanta.actpub.ActPubLog;
import quanta.actpub.model.APList;
import quanta.actpub.model.APObj;
import quanta.config.NodeName;
import quanta.config.ServiceBase;
import quanta.exception.NodeAuthFailedException;
import quanta.exception.base.RuntimeEx;
import quanta.instrument.PerfMon;
import quanta.model.NodeInfo;
import quanta.model.PropertyInfo;
import quanta.model.client.NodeLink;
import quanta.model.client.Attachment;
import quanta.model.client.Constant;
import quanta.model.client.NodeProp;
import quanta.model.client.NodeType;
import quanta.model.client.PrincipalName;
import quanta.model.client.PrivilegeType;
import quanta.model.ipfs.file.IPFSDirStat;
import quanta.mongo.CreateNodeLocation;
import quanta.mongo.MongoSession;
import quanta.mongo.model.AccessControl;
import quanta.mongo.model.SubNode;
import quanta.request.AppDropRequest;
import quanta.request.CreateSubNodeRequest;
import quanta.request.DeletePropertyRequest;
import quanta.request.InsertNodeRequest;
import quanta.request.LikeNodeRequest;
import quanta.request.LinkNodesRequest;
import quanta.request.SaveNodeRequest;
import quanta.request.SearchAndReplaceRequest;
import quanta.request.SplitNodeRequest;
import quanta.request.SubGraphHashRequest;
import quanta.request.TransferNodeRequest;
import quanta.request.UpdateHeadingsRequest;
import quanta.response.AppDropResponse;
import quanta.response.CreateSubNodeResponse;
import quanta.response.DeletePropertyResponse;
import quanta.response.InsertNodeResponse;
import quanta.response.LikeNodeResponse;
import quanta.response.LinkNodesResponse;
import quanta.response.SaveNodeResponse;
import quanta.response.SearchAndReplaceResponse;
import quanta.response.SplitNodeResponse;
import quanta.response.SubGraphHashResponse;
import quanta.response.TransferNodeResponse;
import quanta.response.UpdateHeadingsResponse;
import quanta.types.TypeBase;
import quanta.util.IntVal;
import quanta.util.SubNodeUtil;
import quanta.util.ThreadLocals;
import quanta.util.Util;
import quanta.util.Val;
import quanta.util.XString;

/**
 * Service for editing content of nodes. That is, this method updates property values of nodes. As
 * the user is using the application and moving, copy+paste, or editing node content this is the
 * service that performs those operations on the server, directly called from the HTML 'controller'
 */
@Component
public class NodeEditService extends ServiceBase {
	private static final Logger log = LoggerFactory.getLogger(NodeEditService.class);

	@Autowired
	private ActPubLog apLog;

	/*
	 * Creates a new node as a *child* node of the node specified in the request. Should ONLY be called
	 * by the controller that accepts a node being created by the GUI/user
	 */
	public CreateSubNodeResponse createSubNode(MongoSession ms, CreateSubNodeRequest req) {
		// log.debug("createSubNode");
		CreateSubNodeResponse res = new CreateSubNodeResponse();

		boolean linkBookmark = "linkBookmark".equals(req.getPayloadType());
		String nodeId = req.getNodeId();
		boolean makePublicWritable = false;
		boolean allowSharing = true;
		SubNode parentNode = null;

		/*
		 * If this is a "New Post" from the Feed tab we get here with no ID but we put this in user's
		 * "My Posts" node
		 */
		if (no(nodeId) && !linkBookmark) {
			parentNode = read.getUserNodeByType(ms, null, null, "### " + ThreadLocals.getSC().getUserName() + "'s Public Posts",
					NodeType.POSTS.s(), Arrays.asList(PrivilegeType.READ.s()), NodeName.POSTS);

			if (ok(parentNode)) {
				nodeId = parentNode.getIdStr();
				makePublicWritable = true;
			}
		}

		/* Node still null, then try other ways of getting it */
		if (no(parentNode) && !linkBookmark) {
			if (nodeId.equals("~" + NodeType.NOTES.s())) {
				parentNode = read.getUserNodeByType(ms, ms.getUserName(), null, "### Notes", NodeType.NOTES.s(), null, null);
			} else {
				parentNode = read.getNode(ms, nodeId);
			}
		}

		// lets the type override the location where the node is created.
		TypeBase plugin = typePluginMgr.getPluginByType(req.getTypeName());
		if (ok(plugin)) {
			Val<SubNode> vcNode = new Val<>(parentNode);
			plugin.preCreateNode(ms, vcNode, req, linkBookmark);
			parentNode = vcNode.getVal();
		}

		if (no(parentNode)) {
			throw new RuntimeException("unable to locate parent for insert");
		}

		/*
		 * We don't allow the admin user to create nodes under someone elses account. This is mainly being
		 * done as a last resort catch because I sometimes forget I'm logged in as 'admin' and start to
		 * reply to something when I meant to be in as a normal user.
		 */
		if (ms.isAdmin() && !acl.isAdminOwned(parentNode)) {
			throw new RuntimeException("Admin not allowed to create nodes under non-admin nodes.");
		}

		auth.authForChildNodeCreate(ms, parentNode);
		parentNode.adminUpdate = true;

		// note: redundant security
		if (acl.isAdminOwned(parentNode) && !ms.isAdmin()) {
			throw new NodeAuthFailedException();
		}

		CreateNodeLocation createLoc = req.isCreateAtTop() ? CreateNodeLocation.FIRST : CreateNodeLocation.LAST;
		SubNode newNode =
				create.createNode(ms, parentNode, null, req.getTypeName(), 0L, createLoc, req.getProperties(), null, true, false);

		if (req.isPendingEdit()) {
			mongoUtil.setPendingPath(newNode, true);
		}

		newNode.setContent(ok(req.getContent()) ? req.getContent() : "");
		newNode.touch();

		if (NodeType.BOOKMARK.s().equals(req.getTypeName())) {
			newNode.set(NodeProp.TARGET_ID, req.getNodeId());

			// adding bookmark should disallow sharing.
			allowSharing = false;
		}

		if (req.isTypeLock()) {
			newNode.set(NodeProp.TYPE_LOCK, Boolean.valueOf(true));
		}

		// If we're inserting a node under the POSTS it should be public, rather than inherit.
		if (parentNode.isType(NodeType.POSTS)) {
			makePublicWritable = true;
		}

		if (allowSharing) {
			// if a user to share to (a Direct Message) is provided, add it.
			if (ok(req.getShareToUserId())) {
				HashMap<String, AccessControl> ac = new HashMap<>();
				ac.put(req.getShareToUserId(), new AccessControl(null, APConst.RDWR));
				newNode.setAc(ac);
			}
			// else maybe public.
			else if (makePublicWritable) {
				acl.addPrivilege(ms, null, newNode, PrincipalName.PUBLIC.s(), null,
						Arrays.asList(PrivilegeType.READ.s(), PrivilegeType.WRITE.s()), null);
			}
			// else add default sharing
			else {
				// we always determine the access controls from the parent for any new nodes
				auth.setDefaultReplyAcl(parentNode, newNode);

				// inherit UNPUBLISHED prop from parent, if we own the parent
				if (parentNode.getBool(NodeProp.UNPUBLISHED) && parentNode.getOwner().equals(ms.getUserNodeId())) {
					newNode.set(NodeProp.UNPUBLISHED, true);
				}

				String cipherKey = parentNode.getStr(NodeProp.ENC_KEY);
				if (ok(cipherKey)) {
					res.setEncrypt(true);
				}
			}
		}

		if (!StringUtils.isEmpty(req.getBoostTarget())) {
			/* If the node being boosted is itself a boost then boost the original boost instead */
			SubNode nodeToBoost = read.getNode(ms, req.getBoostTarget());
			if (ok(nodeToBoost)) {
				String innerBoost = nodeToBoost.getStr(NodeProp.BOOST);
				newNode.set(NodeProp.BOOST, ok(innerBoost) ? innerBoost : req.getBoostTarget());
			}
		}

		update.save(ms, newNode);

		/*
		 * if this is a boost node being saved, then immediately run processAfterSave, because we won't be
		 * expecting any final 'saveNode' to ever get called (like when user clicks "Save" in node editor),
		 * because this node will already be final and the user won't be editing it. It's done and ready to
		 * publish out to foreign servers
		 */
		if (!req.isPendingEdit() && ok(req.getBoostTarget())) {
			// log.debug("publishing boost: " + newNode.getIdStr());
			processAfterSave(ms, newNode, parentNode);
		}

		res.setNewNode(convert.convertToNodeInfo(false, ThreadLocals.getSC(), ms, newNode, false, -1, false, false, false, false,
				false, false, null, false));
		res.setSuccess(true);
		return res;
	}

	/*
	 * Creates a new node that is a sibling (same parent) of and at the same ordinal position as the
	 * node specified in the request. Should ONLY be called by the controller that accepts a node being
	 * created by the GUI/user
	 */
	public InsertNodeResponse insertNode(MongoSession ms, InsertNodeRequest req) {
		InsertNodeResponse res = new InsertNodeResponse();
		String parentNodeId = req.getParentId();
		log.debug("Inserting under parent: " + parentNodeId);
		SubNode parentNode = read.getNode(ms, parentNodeId);
		if (no(parentNode)) {
			throw new RuntimeException("Unable to find parent note to insert under: " + parentNodeId);
		}

		/*
		 * We don't allow the admin user to create nodes under someone elses account. This is mainly being
		 * done as a last resort catch because I sometimes forget I'm logged in as 'admin' and start to
		 * reply to something when I meant to be in as a normal user.
		 */
		if (ms.isAdmin() && !acl.isAdminOwned(parentNode)) {
			throw new RuntimeException("Admin not allowed to create nodes under non-admin nodes.");
		}

		auth.authForChildNodeCreate(ms, parentNode);
		parentNode.adminUpdate = true;

		// note: redundant security
		if (acl.isAdminOwned(parentNode) && !ms.isAdmin()) {
			throw new NodeAuthFailedException();
		}

		SubNode newNode = create.createNode(ms, parentNode, null, req.getTypeName(), req.getTargetOrdinal(),
				CreateNodeLocation.ORDINAL, null, null, true, true);

		if (ok(req.getInitialValue())) {
			newNode.setContent(req.getInitialValue());
		} else {
			newNode.setContent("");
		}
		newNode.touch();

		// '/r/p/' = pending (nodes not yet published, being edited created by users)
		if (req.isPendingEdit()) {
			mongoUtil.setPendingPath(newNode, true);
		}

		boolean allowSharing = true;
		if (NodeType.BOOKMARK.s().equals(req.getTypeName())) {
			// adding bookmark should disallow sharing.
			allowSharing = false;
		}

		if (allowSharing) {
			// If we're inserting a node under the POSTS it should be public, rather than inherit.
			// Note: some logic may be common between this insertNode() and the createSubNode()
			if (parentNode.isType(NodeType.POSTS)) {
				acl.addPrivilege(ms, null, newNode, PrincipalName.PUBLIC.s(), null,
						Arrays.asList(PrivilegeType.READ.s(), PrivilegeType.WRITE.s()), null);
			} else {
				// we always copy the access controls from the parent for any new nodes
				auth.setDefaultReplyAcl(parentNode, newNode);

				// inherit UNPUBLISHED prop from parent, if we own the parent
				if (parentNode.getBool(NodeProp.UNPUBLISHED) && parentNode.getOwner().equals(ms.getUserNodeId())) {
					newNode.set(NodeProp.UNPUBLISHED, true);
				}
			}
		}

		// use this is for testing non string types
		// newNode.set(NodeProp.ACT_PUB_OBJ_URLS, Arrays.asList(//
		// new APOUrl("Link", "text/html", "https://drudgereport.com"),
		// new APOUrl("Link", "text/html", "https://cnn.com")));
		// newNode.set(NodeProp.ACT_PUB_OBJ_ICONS, Arrays.asList(//
		// new APOIcon("Icon", "image/png",
		// "https://pbs.twimg.com/media/FhpmO98UUAAB2Cm?format=png&name=small"),
		// new APOIcon("Icon", "image/jpg",
		// "https://pbs.twimg.com/media/FhpfAFNUUAAwjFR?format=jpg&name=small")));
		// newNode.set(NodeProp.ACT_PUB_OBJ_NAME, "Test Name");

		// createNode might have altered 'hasChildren', so we save if dirty
		update.saveIfDirty(ms, parentNode);

		// We save this right away, before calling convertToNodeInfo in case that method does any Db related
		// stuff
		// where it's expecting the node to exist.
		update.save(ms, newNode);

		res.setNewNode(convert.convertToNodeInfo(false, ThreadLocals.getSC(), ms, newNode, false, -1, false, false, false, false,
				false, false, null, false));

		// if (req.isUpdateModTime() && !StringUtils.isEmpty(newNode.getContent()) //
		// // don't evern send notifications when 'admin' is the one doing the editing.
		// && !PrincipalName.ADMIN.s().equals(sessionContext.getUserName())) {
		// outboxMgr.sendNotificationForNodeEdit(newNode, sessionContext.getUserName());
		// }

		res.setSuccess(true);

		// log.debug("SAVE NODE: " + XString.prettyPrint(newNode));
		return res;
	}

	public SubNode createFriendNode(MongoSession ms, SubNode parentFriendsList, String userToFollow) {

		// get userNode of user to follow
		SubNode userNode = read.getUserNodeByUserName(ms, userToFollow, false);
		if (ok(userNode)) {
			List<PropertyInfo> properties = new LinkedList<>();
			properties.add(new PropertyInfo(NodeProp.USER.s(), userToFollow));
			properties.add(new PropertyInfo(NodeProp.USER_NODE_ID.s(), userNode.getIdStr()));

			SubNode newNode = create.createNode(ms, parentFriendsList, null, NodeType.FRIEND.s(), 0L, CreateNodeLocation.LAST,
					properties, parentFriendsList.getOwner(), true, true);
			newNode.set(NodeProp.TYPE_LOCK, Boolean.valueOf(true));

			String userToFollowActorId = userNode.getStr(NodeProp.ACT_PUB_ACTOR_ID);
			if (ok(userToFollowActorId)) {
				newNode.set(NodeProp.ACT_PUB_ACTOR_ID, userToFollowActorId);
			}

			String userToFollowActorUrl = userNode.getStr(NodeProp.ACT_PUB_ACTOR_URL);
			if (ok(userToFollowActorUrl)) {
				newNode.set(NodeProp.ACT_PUB_ACTOR_URL, userToFollowActorUrl);
			}

			// log.debug("Saving Friend Node (as a Follow): " + XString.prettyPrint(newNode));

			/////////////////////////////////////////////////////////////////////////////////////
			// Leaving this temporary code here for now.
			// todo-1: this block was temporary troubleshooting
			// Criteria crit = Criteria.where(SubNode.OWNER).is(ms.getUserNodeId()) //
			// .and(SubNode.PROPS + "." + NodeProp.USER_NODE_ID.s()).is(userNode.getIdStr()) //
			// .and(SubNode.TYPE).is(NodeType.FRIEND.s());

			// Query q = new Query();
			// q.addCriteria(crit);
			// SubNode ret = mongoUtil.findOne(q);
			// if (ok(ret)) {
			// log.debug("oops!! duplicates this existing FRIEND node: " + XString.prettyPrint(ret));
			// throw new RuntimeException("Duplicate Friend: " + userToFollow);
			// }

			// troubleshooting this constraint violation
			// ops.indexOps(SubNode.class).ensureIndex(//
			// new Index().on(SubNode.OWNER, Direction.ASC) //
			// .on(SubNode.PROPS + "." + NodeProp.USER_NODE_ID.s(), Direction.ASC) //
			// .unique() //
			// .named(indexName) //
			// .partial(PartialIndexFilter.of(Criteria.where(SubNode.TYPE).is(NodeType.FRIEND.s()))));
			///////////////////////////////////////////////////////////////////////////////////////////////

			update.save(ms, newNode);
			return newNode;
		} else {
			throw new RuntimeException("User not found: " + userToFollow);
		}
	}

	public AppDropResponse appDrop(MongoSession ms, AppDropRequest req) {
		AppDropResponse res = new AppDropResponse();
		String data = req.getData();
		String lcData = data.toLowerCase();

		// for now we only support dropping of links onto our window. I threw in
		// 'file://' but i have no idea
		// if that's going to work or not (yet)
		if (!lcData.startsWith("http://") && !lcData.startsWith("https://") && !lcData.startsWith("file://")) {
			log.info("Drop even ignored: " + data);
			res.setMessage("Sorry, can't drop that there.");
			return res;
		}

		SubNode linksNode = read.getUserNodeByType(ms, ms.getUserName(), null, "### Notes", NodeType.NOTES.s(), null, null);

		if (no(linksNode)) {
			log.warn("unable to get linksNode");
			return null;
		}

		SubNode newNode =
				create.createNode(ms, linksNode, null, NodeType.NONE.s(), 0L, CreateNodeLocation.LAST, null, null, true, true);

		String title = lcData.startsWith("http") ? Util.extractTitleFromUrl(data) : null;
		String content = ok(title) ? "#### " + title + "\n" : "";
		content += data;
		newNode.setContent(content);
		newNode.touch();
		update.save(ms, newNode);

		res.setMessage("Drop Accepted: Created link to: " + data);
		return res;
	}

	@PerfMon(category = "edit")
	public LikeNodeResponse likeNode(MongoSession ms, LikeNodeRequest req) {
		LikeNodeResponse res = new LikeNodeResponse();
		// log.debug("LikeNode saveNode");

		exec.run(() -> {
			arun.run(as -> {
				SubNode node = read.getNode(ms, req.getId());
				if (no(node)) {
					throw new RuntimeException("Unable to find node: " + req.getId());
				}
				if (no(node.getLikes())) {
					node.setLikes(new HashSet<>());
				}

				String userName = ThreadLocals.getSC().getUserName();
				// String actorUrl = apUtil.makeActorUrlForUserName(userName); // long name not used.

				// local users will always just have their userName put in the 'likes'
				if (req.isLike()) {
					if (node.getLikes().add(userName)) {
						// set node to dirty only if it just changed.
						ThreadLocals.dirty(node);

						// if this is a foreign post send message out to fediverse
						if (ok(node.getStr(NodeProp.ACT_PUB_ID))) {
							apub.sendLikeMessage(as, ms.getUserName(), node);
						}
					}
				} else {
					if (node.getLikes().remove(userName)) {
						// set node to dirty only if it just changed.
						ThreadLocals.dirty(node);

						// todo-1: send undo to foreign server
						// if likes set is now empty make it null.
						if (node.getLikes().size() == 0) {
							node.setLikes(null);
						}
					}
				}

				return null;
			});
		});
		return res;
	}

	@PerfMon(category = "edit")
	public SaveNodeResponse saveNode(MongoSession ms, SaveNodeRequest req) {
		SaveNodeResponse res = new SaveNodeResponse();
		NodeInfo nodeInfo = req.getNode();
		String nodeId = nodeInfo.getId();

		// log.debug("saveNode. nodeId=" + XString.prettyPrint(nodeInfo));
		SubNode node = read.getNode(ms, nodeId);
		auth.ownerAuth(ms, node);

		// remove orphaned attachments
		removeDeletedAttachments(ms, node, req.getNode().getAttachments());

		// set new attachments
		node.setAttachments(req.getNode().getAttachments());
		attach.fixAllAttachmentMimes(node);

		node.setLinks(req.getNode().getLinks());

		/*
		 * The only purpose of this limit is to stop hackers from using up lots of space, because our only
		 * current quota is on attachment file size uploads
		 */
		if (ok(nodeInfo.getContent()) && nodeInfo.getContent().length() > 64 * 1024) {
			throw new RuntimeEx("Max text length is 64K");
		}

		/* If current content exists content is being edited reset likes */
		if (ok(node.getContent()) && node.getContent().trim().length() > 0
				&& !Util.equalObjs(node.getContent(), nodeInfo.getContent())) {
			node.setLikes(null);
		}

		node.setContent(nodeInfo.getContent());
		node.setTags(nodeInfo.getTags());
		node.touch();
		node.setType(nodeInfo.getType());

		/*
		 * if node name is empty or not valid (canot have ":" in the name) set it to null quietly
		 */
		if (StringUtils.isEmpty(nodeInfo.getName())) {
			node.setName(null);
		}
		// if we're setting node name to a different node name
		else if (ok(nodeInfo.getName()) && nodeInfo.getName().length() > 0 && !nodeInfo.getName().equals(node.getName())) {
			if (!snUtil.validNodeName(nodeInfo.getName())) {
				throw new RuntimeEx("Node names can only contain letter, digit, underscore, dash, and period characters.");
			}
			String nodeName = nodeInfo.getName().trim();

			// if not admin we have to lookup the node with "userName:nodeName" format
			if (!ThreadLocals.getSC().isAdmin()) {
				nodeName = ThreadLocals.getSC().getUserName() + ":" + nodeName;
			}

			SubNode nodeByName = read.getNodeByName(ms, nodeName);

			// delete if orphan (but be safe and double check we aren't deleting `nodeId` node)
			if (ok(nodeByName) && !nodeId.equals(nodeByName.getIdStr()) && read.isOrphan(nodeByName.getPath())) {

				// if we don't be sure to delete this orphan we might end up with a constraint violation
				// on the node name unique index.
				delete.directDelete(nodeByName);
				nodeByName = null;
			}

			if (ok(nodeByName)) {
				throw new RuntimeEx("Node name is already in use. Duplicates not allowed.");
			}

			node.setName(nodeInfo.getName().trim());
		}

		String sig = null;
		if (ok(nodeInfo.getProperties())) {
			for (PropertyInfo property : nodeInfo.getProperties()) {
				if (NodeProp.CRYPTO_SIG.s().equals(property.getName())) {
					sig = (String) property.getValue();
					// log.debug("Got Sig in Save: " + sig);
				}

				if ("[null]".equals(property.getValue())) {
					node.delete(property.getName());
				} else {
					/*
					 * save only if server determines the property is savable. Just protection. Client shouldn't be
					 * trying to save stuff that is illegal to save, but we have to assume the worst behavior from
					 * client code, for security and robustness.
					 */
					if (ms.isAdmin() || SubNodeUtil.isReadonlyProp(property.getName())) {
						// log.debug("Property to save: " + property.getName() + "=" +
						// property.getValue());
						node.set(property.getName(), property.getValue());
					} else {
						/**
						 * TODO: This case indicates that data was sent unnecessarily. fix! (i.e. make sure this block
						 * cannot ever be entered)
						 */
						log.debug("Ignoring unneeded save attempt on unneeded prop: " + property.getName());
					}
				}
			}
		}

		// if not encrypted remove ENC_KEY too. It won't be doing anything in this case.
		if (ok(nodeInfo.getContent()) && !nodeInfo.getContent().startsWith(Constant.ENC_TAG.s())) {
			node.delete(NodeProp.ENC_KEY);
		}

		// If removing encryption, remove it from all the ACL entries too.
		String encKey = node.getStr(NodeProp.ENC_KEY);
		if (no(encKey)) {
			mongoUtil.removeAllEncryptionKeys(node);
		}
		/* if node is currently encrypted */
		else {
			res.setAclEntries(auth.getAclEntries(ms, node));
		}

		attach.pinLocalIpfsAttachments(node);

		/*
		 * If the node being saved is currently in the pending area /p/ then we publish it now, and move it
		 * out of pending.
		 */
		mongoUtil.setPendingPath(node, false);

		// todo-2: for now only admin user is REQUIRED to have signed nodes.
		if (prop.isRequireCrypto() && ms.isAdmin()) {
			if (!crypto.nodeSigVerify(node, sig)) {
				// stop this node from being saved with 'clean'
				ThreadLocals.clean(node);
				log.debug("Save request failed on bad signature.");
				throw new RuntimeException("Signature failed.");
			}
		}

		TypeBase plugin = typePluginMgr.getPluginByType(node.getType());
		if (ok(plugin)) {
			plugin.beforeSaveNode(ms, node);
		}

		String sessionUserName = ThreadLocals.getSC().getUserName();

		SubNode parent = read.getParent(ms, node, false);
		if (ok(parent)) {
			parent.setHasChildren(true);
		}

		/*
		 * Send notification to local server or to remote server when a node is added (and not by admin)
		 */
		if (!PrincipalName.ADMIN.s().equals(sessionUserName)) {
			processAfterSave(ms, node, parent);
		}

		NodeInfo newNodeInfo = convert.convertToNodeInfo(false, ThreadLocals.getSC(), ms, node, false, -1, false, false, true,
				false, true, true, null, false);
		if (ok(newNodeInfo)) {
			res.setNode(newNodeInfo);
		}

		// todo-2: for now we only push nodes if public, up to browsers rather than doing a specific check
		// to send only to users who should see it.
		if (AclService.isPublic(ms, node)) {
			push.pushTimelineUpdateToBrowsers(ms, newNodeInfo);
		}

		res.setSuccess(true);
		return res;
	}

	// Removes all attachments from 'node' that are not on 'newAttrs'
	public void removeDeletedAttachments(MongoSession ms, SubNode node, HashMap<String, Attachment> newAtts) {
		if (no(node.getAttachments()))
			return;

		// we need toDelete as separate list to avoid "concurrent modification exception" by deleting
		// from the attachments set during iterating it.
		List<String> toDelete = new LinkedList<>();

		node.getAttachments().forEach((key, att) -> {
			if (no(newAtts) || !newAtts.containsKey(key)) {
				toDelete.add(key);
			}
		});

		// run these actual deletes in a separate async thread
		arun.run(as -> {
			for (String key : toDelete) {
				attach.deleteBinary(ms, key, node, null, false);
			}
			return null;
		});
	}

	// 'parent' (of 'node') can be passed in if already known, or else null can be passed for
	// parent and we get the parent automatically in here
	public void processAfterSave(MongoSession ms, SubNode node, SubNode parent) {
		// never do any of this logic if this is an admin-owned node being saved.
		if (acl.isAdminOwned(node)) {
			return;
		}

		if (no(parent)) {
			parent = read.getParent(ms, node, false);
		}
		final SubNode _parent = parent;
		arun.run(s -> {
			HashSet<Integer> sessionsPushed = new HashSet<>();
			boolean isAccnt = node.isType(NodeType.ACCOUNT);

			if (node.isType(NodeType.FRIEND)) {
				ThreadLocals.getSC().setFriendsTagsDirty(true);
			}

			// push any chat messages that need to go out.
			if (!isAccnt) {
				push.pushNodeToBrowsers(s, sessionsPushed, node);
			}

			if (ok(_parent)) {
				if (!isAccnt) {
					HashMap<String, APObj> tags = auth.parseTags(node.getContent(), true, true);

					if (ok(tags) && tags.size() > 0) {
						String userDoingAction = ThreadLocals.getSC().getUserName();
						apub.importUsers(ms, tags, userDoingAction);
						auth.saveMentionsToACL(tags, s, node);
						node.set(NodeProp.ACT_PUB_TAG, new APList(new LinkedList(tags.values())));
						update.save(ms, node);
					}
				}

				// if this is an account type then don't expect it to have any ACL but we still want to broadcast
				// out to the world the edit that was made to it, as long as it's not admin owned.
				boolean forceSendToPublic = isAccnt;

				if (forceSendToPublic || ok(node.getAc())) {
					// We only send COMMENTS out to ActivityPub servers, and also only if "not unpublished"
					if (!node.getBool(NodeProp.UNPUBLISHED) && node.getType().equals(NodeType.COMMENT.s())) {
						// This broadcasts out to the shared inboxes of all the followers of the user
						apub.sendObjOutbound(s, _parent, node, forceSendToPublic);
					}

					push.pushNodeUpdateToBrowsers(s, sessionsPushed, node);
				}

				if (AclService.isPublic(ms, node) && !StringUtils.isEmpty(node.getName())) {
					saveNodeToMFS(ms, node);
				}

				return null;
			} else {
				log.error("Unable to find parent node for path: " + node.getPath());
			}

			return null;
		});
	}

	/*
	 * Save PUBLIC nodes to IPFS/MFS
	 */
	public void saveNodeToMFS(MongoSession ms, SubNode node) {
		if (!ThreadLocals.getSC().allowWeb3()) {
			return;
		}

		// Note: we need to access the current thread, because the rest of the logic runs in a damon thread.
		String userNodeId = ThreadLocals.getSC().getUserNodeId().toHexString();

		exec.run(() -> {
			arun.run(as -> {
				SubNode ownerNode = read.getNode(as, node.getOwner());

				// only write out files if user has MFS enabled in their UserProfile
				if (!ownerNode.getBool(NodeProp.MFS_ENABLE)) {
					return null;
				}

				if (no(ownerNode)) {
					throw new RuntimeException("Unable to find owner node.");
				}

				String pathBase = "/" + userNodeId;

				// **** DO NOT DELETE *** (this code works and is how we could use the 'path' to store our files,
				// for a tree on a user's MFS area
				// but what we do instead is take the NAME of the node, and use that is the filename, and write
				// directly into '[user]/posts/[name]' loation
				// // make the path of the node relative to the owner by removing the part of the path that is
				// // the user's root node path
				// String path = node.getPath().replace(ownerNode.getPath(), "");
				// path = folderizePath(path);

				// If this gets to be too many files for IPFS to handle, we can always include a year and month, and
				// that would probably
				// at least create a viable system, proof-of-concept
				String path = "/" + node.getName() + ".txt";

				String mfsPath = pathBase + "/posts" + path;
				// log.debug("Writing JSON to MFS Path: " + mfsPath);

				// save values for finally block
				String mcid = node.getMcid();
				String prevMcid = node.getPrevMcid();

				try {
					// intentionally not using setters here (becasue of dirty flag)
					node.mcid = null;
					node.prevMcid = null;

					// todo-1: quick hack: i keep seeing tag="" in the JSON, but don't want to check that now.
					// need to fix this the correct way.
					if ("".equals(node.getTags())) {
						node.setTags(null);
					}

					// for now let's just write text
					// ipfsFiles.addFile(as, mfsPath, MediaType.APPLICATION_JSON_VALUE, XString.prettyPrint(node));
					ipfsFiles.addFile(as, mfsPath, MediaType.TEXT_PLAIN_VALUE, node.getContent());
				} finally {
					// retore values after done with json serializing (do NOT use setter methods here)
					node.mcid = mcid;
					node.prevMcid = prevMcid;
				}

				IPFSDirStat pathStat = ipfsFiles.pathStat(mfsPath);
				if (ok(pathStat)) {
					log.debug("File PathStat: " + XString.prettyPrint(pathStat));
					node.setPrevMcid(mcid);
					node.setMcid(pathStat.getHash());
				}

				// pathStat = ipfsFiles.pathStat(pathBase);
				// if (ok(pathStat)) {
				// log.debug("Parent Folder PathStat " + pathBase + ": " + XString.prettyPrint(pathStat));
				// }

				// IPFSDir dir = ipfsFiles.getDir(pathBase);
				// if (ok(dir)) {
				// log.debug("Parent Folder Listing " + pathBase + ": " + XString.prettyPrint(dir));
				// }

				return null;
			});
		});
	}

	/*
	 * Since Quanta stores nodes under other nodes, and file systems are not capable of doing this we
	 * have to convert names to folders by putting a "-f" on them before writing to MFS
	 */
	private String folderizePath(String path) {
		List<String> nameTokens = XString.tokenize(path, "/", true);
		StringBuilder sb = new StringBuilder();
		int idx = 0;
		for (String tok : nameTokens) {
			if (idx < nameTokens.size()) {
				sb.append("/");
			}

			if (idx < nameTokens.size() - 1) {
				sb.append(tok + "-f");
			} else {
				sb.append(tok);
			}
			idx++;
		}
		return sb.toString();
	}

	/*
	 * Whenever a friend node is saved, we send the "following" request to the foreign ActivityPub
	 * server
	 */
	public void updateSavedFriendNode(String userDoingAction, SubNode node) {
		String userNodeId = node.getStr(NodeProp.USER_NODE_ID);

		String friendUserName = node.getStr(NodeProp.USER);
		if (ok(friendUserName)) {
			// if a foreign user, update thru ActivityPub.
			if (userDoingAction.equals(PrincipalName.ADMIN.s())) {
				throw new RuntimeException("Don't follow from admin account.");
			}

			if (friendUserName.contains("@")) {
				apLog.trace("calling setFollowing=true, to post follow to foreign server.");
				apFollowing.setFollowing(userDoingAction, friendUserName, true);
			}

			/*
			 * when user first adds, this friendNode won't have the userNodeId yet, so add if not yet existing
			 */
			if (no(userNodeId)) {
				/*
				 * A userName containing "@" is considered a foreign Fediverse user and will trigger a WebFinger
				 * search of them, and a load/update of their outbox
				 */
				if (friendUserName.contains("@")) {
					exec.run(() -> {
						arun.run(s -> {
							if (!ThreadLocals.getSC().isAdmin()) {
								apub.getAcctNodeByForeignUserName(s, userDoingAction, friendUserName, false, true);
							}

							/*
							 * The only time we pass true to load the user into the system is when they're being added as a
							 * friend.
							 */
							apub.userEncountered(friendUserName, true);
							return null;
						});
					});
				}

				Val<SubNode> userNode = new Val<SubNode>();
				arun.run(s -> {
					userNode.setVal(read.getUserNodeByUserName(s, friendUserName));
					return null;
				});

				if (ok(userNode.getVal())) {
					userNodeId = userNode.getVal().getIdStr();
					node.set(NodeProp.USER_NODE_ID, userNodeId);
				}
			}
		}
	}

	/*
	 * Removes the property specified in the request from the node specified in the request
	 */
	public DeletePropertyResponse deleteProperties(MongoSession ms, DeletePropertyRequest req) {
		DeletePropertyResponse res = new DeletePropertyResponse();
		String nodeId = req.getNodeId();
		SubNode node = read.getNode(ms, nodeId);
		auth.ownerAuth(node);

		for (String propName : req.getPropNames()) {
			node.delete(propName);
		}

		update.save(ms, node);
		res.setSuccess(true);
		return res;
	}

	/*
	 * When user pastes in a large amount of text and wants to have this text broken out into individual
	 * nodes they can pass into here and double spaces become splitpoints, and this splitNode method
	 * will break it all up into individual nodes.
	 * 
	 * req.splitType == 'inline' || 'children'
	 */
	public SplitNodeResponse splitNode(MongoSession ms, SplitNodeRequest req) {
		SplitNodeResponse res = new SplitNodeResponse();
		String nodeId = req.getNodeId();

		// log.debug("Splitting node: " + nodeId);
		SubNode node = read.getNode(ms, nodeId);
		SubNode parentNode = read.getParent(ms, node);

		auth.ownerAuth(ms, node);
		String content = node.getContent();
		boolean containsDelim = content.contains(req.getDelimiter());

		/*
		 * If split will have no effect, just return as if successful.
		 */
		if (!containsDelim) {
			res.setSuccess(true);
			return res;
		}

		String[] contentParts = StringUtils.splitByWholeSeparator(content, req.getDelimiter());
		SubNode parentForNewNodes = null;
		long firstOrdinal = 0;

		/*
		 * When inserting inline all nodes go in right where the original node is, in order below it as
		 * siblings
		 */
		if (req.getSplitType().equalsIgnoreCase("inline")) {
			parentForNewNodes = parentNode;
			firstOrdinal = node.getOrdinal();
		}
		/*
		 * but for a 'child' insert all new nodes are inserted as children of the original node, starting at
		 * the top (ordinal), regardless of whether this node already has any children or not.
		 */
		else {
			parentForNewNodes = node;
			firstOrdinal = 0L;
		}

		int numNewSlots = contentParts.length - 1;
		if (numNewSlots > 0) {
			firstOrdinal = create.insertOrdinal(ms, parentForNewNodes, firstOrdinal, numNewSlots);
			update.save(ms, parentForNewNodes);
		}

		int idx = 0;
		for (String part : contentParts) {
			// log.debug("ContentPart[" + idx + "] " + part);
			part = part.trim();
			if (idx == 0) {
				node.setContent(part);
				node.setOrdinal(firstOrdinal);
				node.touch();
				update.save(ms, node);
			} else {
				SubNode newNode =
						create.createNode(ms, parentForNewNodes, null, firstOrdinal + idx, CreateNodeLocation.ORDINAL, false);
				newNode.setContent(part);
				newNode.touch();
				update.save(ms, newNode);
			}
			idx++;
		}

		if (req.getSplitType().equalsIgnoreCase("children")) {
			parentForNewNodes.setHasChildren(true);
		}

		res.setSuccess(true);
		return res;
	}

	/*
	 * This method will eventually use push+recieve to send node data down to the browser, but I'm
	 * putting here for now the ability to use it (temporarily) as a SHA-256 hash generator that
	 * generates the hash of all subnodes, and will just stick thas hash into a property on the top
	 * parent node (req.nodeId)
	 */
	public SubGraphHashResponse subGraphHash(MongoSession ms, SubGraphHashRequest req) {
		SubGraphHashResponse res = new SubGraphHashResponse();
		String nodeId = req.getNodeId();
		SubNode node = read.getNode(ms, nodeId);
		auth.ownerAuth(ms, node);
		String prevHash = null;
		String newHash = null;

		try {
			long totalBytes = 0;
			long nodeCount = 0;
			MessageDigest digest = MessageDigest.getInstance("SHA-256");

			if (req.isRecursive()) {
				StringBuilder sb = new StringBuilder();
				for (SubNode n : read.getSubGraph(ms, node, Sort.by(Sort.Direction.ASC, SubNode.PATH), 0, true, false, false)) {
					nodeCount++;
					sb.append(n.getPath());
					sb.append("-");
					sb.append(n.getOwner().toHexString());
					sb.append(StringUtils.isNotEmpty(n.getContent()) + "-" + n.getContent());

					List<Attachment> atts = n.getOrderedAttachments();
					if (ok(atts) && atts.size() > 0) {
						for (Attachment att : atts) {
							if (ok(att.getBin())) {
								sb.append(StringUtils.isNotEmpty(n.getContent()) + "-bin" + att.getBin());
							}
							if (ok(att.getBinData())) {
								sb.append(StringUtils.isNotEmpty(n.getContent()) + "-bindat" + att.getBinData());
							}
						}
					}

					if (sb.length() > 4096) {
						byte[] b = sb.toString().getBytes(StandardCharsets.UTF_8);
						totalBytes += b.length;
						digest.update(b);
						sb.setLength(0);
					}
				}
				if (sb.length() > 0) {
					byte[] b = sb.toString().getBytes(StandardCharsets.UTF_8);
					totalBytes += b.length;
					digest.update(b);
				}
			}
			byte[] encodedHash = digest.digest();

			newHash = String.valueOf(nodeCount) + " nodes, " + String.valueOf(totalBytes) + " bytes: " + bytesToHex(encodedHash);
			prevHash = node.getStr(NodeProp.SUBGRAPH_HASH);
			node.set(NodeProp.SUBGRAPH_HASH, newHash);

		} catch (Exception e) {
			res.setMessage("Failed generating hash");
			res.setSuccess(false);
			return res;
		}

		boolean hashChanged = ok(prevHash) && !prevHash.equals(newHash);

		res.setMessage((hashChanged ? "Hash CHANGED: " : (no(prevHash) ? "New Hash: " : "Hash MATCHED!: ")) + newHash);
		res.setSuccess(true);
		return res;
	}

	// todo-2: Move to utils class.
	private static String bytesToHex(byte[] hash) {
		StringBuilder hexString = new StringBuilder(2 * hash.length);
		for (int i = 0; i < hash.length; i++) {
			String hex = Integer.toHexString(0xff & hash[i]);
			if (hex.length() == 1) {
				hexString.append('0');
			}
			hexString.append(hex);
		}
		return hexString.toString();
	}

	// todo-2: need to be doing a bulk update in here.
	public TransferNodeResponse transferNode(MongoSession ms, TransferNodeRequest req) {
		TransferNodeResponse res = new TransferNodeResponse();

		// make sure only admin will be allowed to specify some arbitrary "fromUser"
		if (!ms.isAdmin()) {
			req.setFromUser(null);
		}

		IntVal ops = new IntVal(0);
		String nodeId = req.getNodeId();

		// get and auth node being transfered
		log.debug("Transfer node: " + nodeId + " operation=" + req.getOperation());

		// we do allowAuth below, not here
		SubNode node = read.getNode(ms, nodeId, false, null);
		if (no(node)) {
			throw new RuntimeEx("Node not found: " + nodeId);
		}

		// get user node of person being transfered to
		SubNode toUserNode = null;
		if (req.getOperation().equals("transfer")) {
			toUserNode = arun.run(as -> read.getUserNodeByUserName(as, req.getToUser()));
			if (no(toUserNode)) {
				throw new RuntimeEx("User not found: " + req.getToUser());
			}
		}

		// get account node of person doing the transfer
		SubNode fromUserNode = null;
		if (!StringUtils.isEmpty(req.getFromUser())) {
			fromUserNode = arun.run(as -> read.getUserNodeByUserName(as, req.getFromUser()));
			if (no(fromUserNode)) {
				throw new RuntimeEx("User not found: " + req.getFromUser());
			}
		}

		transferNode(ms, req.getOperation(), node, fromUserNode, toUserNode, ops);

		if (req.isRecursive()) {
			// todo-1: make this ONLY query for the nodes that ARE owned by the person doing the transfer,
			// but leave as ALL node for the admin who might specify the 'from'?
			for (SubNode n : read.getSubGraph(ms, node, null, 0, true, false, true)) {
				// log.debug("Node: path=" + path + " content=" + n.getContent());
				transferNode(ms, req.getOperation(), n, fromUserNode, toUserNode, ops);
			}
		}

		if (ops.getVal() > 0) {
			arun.run(as -> {
				update.saveSession(as);
				return null;
			});
		}

		res.setMessage(String.valueOf(ops.getVal()) + " nodes were affected.");
		res.setSuccess(true);
		return res;
	}

	public void transferNode(MongoSession ms, String op, SubNode node, SubNode fromUserNode, SubNode toUserNode, IntVal ops) {
		if (ok(node.getContent()) && node.getContent().startsWith(Constant.ENC_TAG.s())) {
			// for now we silently ignore encrypted nodes during transfers. This needs some more thought
			// (todo-1)
			return;
		}

		/*
		 * if we're transferring only from a specific user (will only be admin able to do this) then we
		 * simply return without doing anything if this node in't owned by the person we're transferring
		 * from
		 */
		if (ok(fromUserNode) && !fromUserNode.getOwner().equals(node.getOwner())) {
			return;
		}

		if (op.equals("transfer")) {
			// if we don't happen do own this node, do nothing.
			if (!ms.getUserNodeId().equals(node.getOwner())) {
				return;
			}
			SubNode ownerAccnt = (SubNode) arun.run(as -> read.getNode(as, node.getOwner()));

			ObjectId fromOwnerId = node.getOwner();
			node.setOwner(toUserNode.getOwner());
			node.setTransferFrom(fromOwnerId);

			// now we ensure that the original owner (before the transfer request) is shared to so they can
			// still see the node
			if (ok(ownerAccnt)) {
				acl.addPrivilege(ms, null, node, null, ownerAccnt, Arrays.asList(PrivilegeType.READ.s(), PrivilegeType.WRITE.s()),
						null);
			}
			node.adminUpdate = true;
			ops.inc();
		} //
		else if (op.equals("accept")) {
			// if we don't happen do own this node, do nothing.
			if (!ms.getUserNodeId().equals(node.getOwner())) {
				return;
			}

			if (ok(node.getTransferFrom())) {
				// get user node of the person pointed to by the 'getTransferFrom' value to share back to them.
				SubNode frmUsrNode = (SubNode) arun.run(as -> read.getNode(as, node.getTransferFrom()));
				if (ok(frmUsrNode)) {
					acl.addPrivilege(ms, null, node, null, frmUsrNode,
							Arrays.asList(PrivilegeType.READ.s(), PrivilegeType.WRITE.s()), null);
				}

				node.setTransferFrom(null);
				node.adminUpdate = true;
				ops.inc();
			}
		} //
		else if (op.equals("reject")) {
			// if we don't happen do own this node, do nothing.
			if (!ms.getUserNodeId().equals(node.getOwner())) {
				return;
			}

			if (ok(node.getTransferFrom())) {
				// get user node of the person pointed to by the 'getTransferFrom' value to share back to them.
				SubNode frmUsrNode = (SubNode) arun.run(as -> read.getNode(as, node.getOwner()));

				node.setOwner(node.getTransferFrom());
				node.setTransferFrom(null);

				if (ok(frmUsrNode)) {
					acl.addPrivilege(ms, null, node, null, frmUsrNode,
							Arrays.asList(PrivilegeType.READ.s(), PrivilegeType.WRITE.s()), null);
				}

				node.adminUpdate = true;
				ops.inc();
			}
		} //
		else if (op.equals("reclaim")) {
			if (ok(node.getTransferFrom())) {
				// if we're reclaiming just make sure the transferFrom was us
				if (!ms.getUserNodeId().equals(node.getTransferFrom())) {
					// skip nodes that don't apply
					return;
				}

				SubNode frmUsrNode = (SubNode) arun.run(as -> read.getNode(as, node.getOwner()));

				node.setOwner(node.getTransferFrom());
				node.setTransferFrom(null);

				if (ok(frmUsrNode)) {
					acl.addPrivilege(ms, null, node, null, frmUsrNode,
							Arrays.asList(PrivilegeType.READ.s(), PrivilegeType.WRITE.s()), null);
				}

				node.adminUpdate = true;
				ops.inc();
			}
		}
	}

	public LinkNodesResponse linkNodes(MongoSession ms, LinkNodesRequest req) {
		LinkNodesResponse res = new LinkNodesResponse();

		SubNode sourceNode = read.getNode(ms, req.getSourceNodeId());
		if (ok(sourceNode)) {
			NodeLink link = new NodeLink();
			link.setNodeId(req.getTargetNodeId());
			link.setName(req.getName());
			sourceNode.addLink(null, link);
		}
		res.setSuccess(true);
		return res;
	}

	/*
	 * This makes ALL the headings of all the sibling nodes match the heading level of the req.nodeId
	 * passed in.
	 */
	public UpdateHeadingsResponse updateHeadings(MongoSession ms, UpdateHeadingsRequest req) {
		UpdateHeadingsResponse res = new UpdateHeadingsResponse();

		SubNode node = read.getNode(ms, req.getNodeId(), true, null);
		String content = node.getContent();
		if (ok(content)) {
			content = content.trim();
			int baseLevel = XString.getHeadingLevel(content);

			SubNode parent = read.getParent(ms, node);
			if (ok(parent)) {
				for (SubNode n : read.getChildren(ms, parent)) {
					updateHeadingsForNode(ms, n, baseLevel);
				}
			}
		}
		return res;
	}

	private void updateHeadingsForNode(MongoSession ms, SubNode node, int level) {
		if (no(node))
			return;

		String nodeContent = node.getContent();
		String content = nodeContent;
		if (no(content))
			return;

		// if this node starts with a heading (hash marks)
		if (content.startsWith("#") && content.indexOf(" ") < 7) {
			int spaceIdx = content.indexOf(" ");
			if (spaceIdx != -1) {

				// strip the pre-existing hashes off
				content = content.substring(spaceIdx + 1);

				/*
				 * These strings (pound sign headings) could be generated dynamically, but this switch with them
				 * hardcoded is more performant
				 */
				switch (level) {
					case 0: // this will be the root node (user selected node)
						break;
					case 1:
						if (!nodeContent.startsWith("# ")) {
							node.setContent("# " + content);
						}
						break;
					case 2:
						if (!nodeContent.startsWith("## ")) {
							node.setContent("## " + content);
						}
						break;
					case 3:
						if (!nodeContent.startsWith("### ")) {
							node.setContent("### " + content);
						}
						break;
					case 4:
						if (!nodeContent.startsWith("#### ")) {
							node.setContent("#### " + content);
						}
						break;
					case 5:
						if (!nodeContent.startsWith("##### ")) {
							node.setContent("##### " + content);
						}
						break;
					case 6:
						if (!nodeContent.startsWith("###### ")) {
							node.setContent("###### " + content);
						}
						break;
					default:
						break;
				}
			}
		}
	}

	/* todo-1: we should be using a bulk update in here */
	public SearchAndReplaceResponse searchAndReplace(MongoSession ms, SearchAndReplaceRequest req) {
		SearchAndReplaceResponse res = new SearchAndReplaceResponse();
		int replacements = 0;
		String nodeId = req.getNodeId();

		// log.debug("searchingAndReplace node: " + nodeId);
		SubNode node = read.getNode(ms, nodeId);
		auth.ownerAuth(ms, node);

		if (replaceText(ms, node, req.getSearch(), req.getReplace())) {
			replacements++;
		}

		if (req.isRecursive()) {
			for (SubNode n : read.getSubGraph(ms, node, null, 0, true, false, true)) {
				if (replaceText(ms, n, req.getSearch(), req.getReplace())) {
					replacements++;
				}
			}
		}

		res.setMessage(String.valueOf(replacements) + " nodes were updated.");
		res.setSuccess(true);
		return res;
	}

	private boolean replaceText(MongoSession ms, SubNode node, String search, String replace) {
		String content = node.getContent();
		if (no(content))
			return false;
		if (content.contains(search)) {
			node.setContent(content.replace(search, replace));
			node.touch();
			return true;
		}
		return false;
	}
}
