package quanta.actpub;

import static quanta.actpub.model.AP.apStr;
import static quanta.util.Util.no;
import static quanta.util.Util.ok;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import quanta.AppController;
import quanta.actpub.model.APList;
import quanta.actpub.model.APOActor;
import quanta.actpub.model.APOAnnounce;
import quanta.actpub.model.APOCreate;
import quanta.actpub.model.APODelete;
import quanta.actpub.model.APOLike;
import quanta.actpub.model.APOMention;
import quanta.actpub.model.APONote;
import quanta.actpub.model.APOPerson;
import quanta.actpub.model.APOTombstone;
import quanta.actpub.model.APOUpdate;
import quanta.actpub.model.APObj;
import quanta.actpub.model.APType;
import quanta.config.ServiceBase;
import quanta.instrument.PerfMon;
import quanta.model.client.Attachment;
import quanta.model.client.Constant;
import quanta.model.client.NodeProp;
import quanta.mongo.MongoSession;
import quanta.mongo.model.SubNode;
import quanta.util.Convert;
import quanta.util.DateUtil;

/**
 * Convenience factory for some types of AP objects
 */
@Component
public class ActPubFactory extends ServiceBase {
	private static final Logger log = LoggerFactory.getLogger(ActPubFactory.class);

	public APObj newUpdateForPerson(String userDoingAction, HashSet<String> toUserNames, String fromActor, boolean privateMessage,
			SubNode node) {
		String objUrl = snUtil.getIdBasedUrl(node);
		ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
		APOPerson payload = generatePersonObj(node);

		return newUpdate(userDoingAction, payload, fromActor, toUserNames, objUrl, now, privateMessage);
	}

	/**
	 * Creates a new 'note' message
	 */
	public APObj newCreateForNote(String userDoingAction, HashSet<String> toUserNames, String fromActor, String inReplyTo,
			String replyToType, String content, String noteUrl, boolean privateMessage, APList attachments) {
		ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
		// log.debug("sending note from actor[" + fromActor + "] inReplyTo[" + inReplyTo);

		APObj payload = newNote(userDoingAction, toUserNames, fromActor, inReplyTo, replyToType, content, noteUrl, now,
				privateMessage, attachments);

		return newCreate(userDoingAction, payload, fromActor, toUserNames, noteUrl, now, privateMessage);
	}

	public APObj newDeleteForNote(String id, String fromActor) {
		APObj payload = new APOTombstone(id);
		return new APODelete(id + "#delete", fromActor, payload, new APList().val(APConst.CONTEXT_STREAMS_PUBLIC));
	}

	public APOLike newLike(String id, String objectId, String actor, List<String> to, List<String> cc) {
		return new APOLike(id, objectId, actor, to, cc);
	}

	/**
	 * Creates a new 'Note' object, depending on what's being replied to.
	 */
	public APObj newNote(String userDoingAction, HashSet<String> toUserNames, String attributedTo /* fromActor */,
			String inReplyTo, String replyToType, String content, String noteUrl, ZonedDateTime now, boolean privateMessage,
			APList attachments) {
		if (ok(content)) {
			// convert all double and single spaced lines to <br> for formatting, for servers that don't
			// understand Markdown
			content = content.replace("\n", "<br>");
		}

		APObj ret = new APONote(noteUrl, now.format(DateTimeFormatter.ISO_INSTANT), attributedTo, null, noteUrl, false, content,
				null);

		if (ok(inReplyTo)) {
			ret = ret.put(APObj.inReplyTo, inReplyTo);
		}

		setRecipients(attributedTo, userDoingAction, ret, toUserNames, privateMessage, true);
		ret.put(APObj.attachment, attachments);
		return ret;
	}

	/**
	 * Creates a new Announce
	 */
	public APObj newAnnounce(String userDoingAction, String actor, String id, HashSet<String> toUserNames,
			String boostTargetActPubId, ZonedDateTime now, boolean privateMessage) {
		APObj ret = new APOAnnounce(actor, id, now.format(DateTimeFormatter.ISO_INSTANT), boostTargetActPubId);
		setRecipients(actor, userDoingAction, ret, toUserNames, privateMessage, false);
		return ret;
	}

	/*
	 * Need to check if this works using the 'to and cc' arrays that are the same as the ones built
	 * above (in newNoteObject() function)
	 */
	public APOCreate newCreate(String userDoingAction, APObj object, String fromActor, HashSet<String> toUserNames,
			String noteUrl, ZonedDateTime now, boolean privateMessage) {
		String idTime = String.valueOf(now.toInstant().toEpochMilli());

		APOCreate ret = new APOCreate(noteUrl + "&apCreateTime=" + idTime, fromActor, //
				now.format(DateTimeFormatter.ISO_INSTANT), object, null);
		setRecipients(fromActor, userDoingAction, ret, toUserNames, privateMessage, true);
		return ret;
	}

	public void setRecipients(String fromActor, String userDoingAction, APObj object, HashSet<String> toUserNames,
			boolean privateMessage, boolean includeTags) {
		List<String> toActors = new LinkedList<>();
		List<String> ccActors = new LinkedList<>();
		APList tagList = includeTags ? new APList() : null;

		for (String userName : toUserNames) {
			try {
				String actorUrl = null;

				// build an actorUrl for either foreign or local users. Both are included.
				if (userName.contains("@")) {
					actorUrl = apUtil.getActorUrlFromForeignUserName(userDoingAction, userName);
					if (no(actorUrl))
						continue;
				} else {
					actorUrl = apUtil.makeActorUrlForUserName(userName);
				}

				// if public message put all the individuals in the 'cc' and "...#Public" as the only 'to', else
				// they go in the 'to'.
				if (!privateMessage) {
					ccActors.add(actorUrl);
				} else {
					toActors.add(actorUrl);
				}

				if (ok(tagList)) {
					// prepend character to make it like '@user@server.com'
					tagList.val(new APOMention(actorUrl, "@" + userName));
				}
			}
			// log and continue if any loop (user) fails here.
			catch (Exception e) {
				log.debug("failed adding user in newCreateMessage: " + userName + " -> " + e.getMessage());
			}
		}

		if (ok(tagList)) {
			object.put(APObj.tag, tagList);
		}

		if (!privateMessage) {
			toActors.add(APConst.CONTEXT_STREAMS_PUBLIC);

			/*
			 * public posts should always cc the followers of the person doing the post (the actor pointed to by
			 * attributedTo)
			 */
			APOActor fromActorObj = (APOActor) arun.run(as -> apUtil.getActorByUrl(as, userDoingAction, fromActor));

			if (ok(fromActorObj)) {
				ccActors.add(fromActorObj.getFollowers());
			}
		}
		
		if (toActors.size() > 0) {
			object.put(APObj.to, new APList().vals(toActors));
		}

		if (ccActors.size() > 0) {
			object.put(APObj.cc, new APList().vals(ccActors));
		}
	}

	public APOUpdate newUpdate(String userDoingAction, APObj object, String fromActor, HashSet<String> toUserNames, String objUrl,
			ZonedDateTime now, boolean privateMessage) {
		String idTime = String.valueOf(now.toInstant().toEpochMilli());
		APOUpdate ret = new APOUpdate(objUrl + "&apCreateTime=" + idTime, fromActor, object, null);
		setRecipients(fromActor, userDoingAction, ret, toUserNames, privateMessage, true);
		return ret;
	}

	public APObj makeAPONote(MongoSession as, String userName, String nodeIdBase, SubNode child) {
		SubNode parent = read.getParent(as, child, false);

		String hexId = child.getIdStr();
		String published = DateUtil.isoStringFromDate(child.getModifyTime());
		String actor = apUtil.makeActorUrlForUserName(userName);

		String content = Convert.replaceTagsWithHtml(child, true);
		if (no(content)) {
			content = child.getContent();
		}

		APONote ret = new APONote(nodeIdBase + hexId, published, actor, null, nodeIdBase + hexId, false, content,
				new APList().val(APConst.CONTEXT_STREAMS_PUBLIC));


		// build the 'tags' array for this object from the sharing ACLs.
		List<String> userNames = apub.getUserNamesFromNodeAcl(as, child);
		if (ok(userNames)) {
			APList tags = apub.getTagListFromUserNames(null, userNames);
			if (ok(tags)) {
				ret.put(APObj.tag, tags);
			}
		}

		if (ok(parent)) {
			String replyTo = apUtil.buildUrlForReplyTo(as, parent);
			if (ok(replyTo)) {
				ret = ret.put(APObj.inReplyTo, replyTo);
			}
		}

		return ret;
	}

	public APObj makeAPOCreateNote(MongoSession as, String userName, String nodeIdBase, SubNode child) {
		SubNode parent = read.getParent(as, child, false);

		String hexId = child.getIdStr();
		String published = DateUtil.isoStringFromDate(child.getModifyTime());
		String actor = apUtil.makeActorUrlForUserName(userName);

		String content = Convert.replaceTagsWithHtml(child, true);
		if (no(content)) {
			content = child.getContent();
		}

		APObj ret = new APONote(nodeIdBase + hexId, published, actor, null, nodeIdBase + hexId, false, content,
				new APList().val(APConst.CONTEXT_STREAMS_PUBLIC));

		if (ok(parent)) {
			String replyTo = apUtil.buildUrlForReplyTo(as, parent);
			if (ok(replyTo)) {
				ret = ret.put(APObj.inReplyTo, replyTo);
			}
		}

		return new APOCreate(
				// todo-2: what is the create=t here? That was part of my own temporary test right?
				nodeIdBase + hexId + "&create=t", actor, published, ret, new APList().val(APConst.CONTEXT_STREAMS_PUBLIC));
	}

	/*
	 * Generates an APOPerson object for one of our own local users
	 */
	@PerfMon(category = "apub")
	public APOPerson generatePersonObj(SubNode userNode) {
		String host = prop.getProtocolHostAndPort();
		String userName = userNode.getStr(NodeProp.USER);

		try {
			user.ensureValidCryptoKeys(userNode);

			String publicKey = userNode.getStr(NodeProp.CRYPTO_KEY_PUBLIC);
			String displayName = userNode.getStr(NodeProp.DISPLAY_NAME);

			String avatarMime = null;
			String avatarVer = null;
			Attachment att = userNode.getAttachment(Constant.ATTACHMENT_PRIMARY.s(), false, false);
			if (ok(att)) {
				avatarMime = att.getMime();
				avatarVer = att.getBin();
			}
			String did = userNode.getStr(NodeProp.USER_DID_IPNS);
			String avatarUrl = prop.getProtocolHostAndPort() + AppController.API_PATH + "/bin/avatar" + "?nodeId="
					+ userNode.getIdStr() + "&v=" + avatarVer;

			APOPerson actor = new APOPerson() //
					/*
					 * Note: this is a self-reference, and must be identical to the URL that returns this object
					 */
					.put(APObj.id, apUtil.makeActorUrlForUserName(userName)) //
					.put(APObj.did, did) //
					.put(APObj.preferredUsername, userName) //
					.put(APObj.name, displayName) //

					.put(APObj.icon, new APObj() //
							.put(APObj.type, APType.Image) //
							.put(APObj.mediaType, avatarMime) //
							.put(APObj.url, avatarUrl));

			Attachment headerAtt = userNode.getAttachment(Constant.ATTACHMENT_HEADER.s(), false, false);
			if (ok(headerAtt)) {
				String headerImageMime = headerAtt.getMime();
				if (ok(headerImageMime)) {
					String headerImageVer = headerAtt.getBin();
					if (ok(headerImageVer)) {
						String headerImageUrl = prop.getProtocolHostAndPort() + AppController.API_PATH + "/bin/profileHeader"
								+ "?nodeId=" + userNode.getIdStr() + "&v=" + headerImageVer;

						actor.put(APObj.image, new APObj() //
								.put(APObj.type, APType.Image) //
								.put(APObj.mediaType, headerImageMime) //
								.put(APObj.url, headerImageUrl));
					}
				}
			}

			actor.put(APObj.summary, userNode.getStr(NodeProp.USER_BIO)) //
					.put(APObj.inbox, host + APConst.PATH_INBOX + "/" + userName) //
					.put(APObj.outbox, host + APConst.PATH_OUTBOX + "/" + userName) //
					.put(APObj.followers, host + APConst.PATH_FOLLOWERS + "/" + userName) //
					.put(APObj.following, host + APConst.PATH_FOLLOWING + "/" + userName) //

					/*
					 * Note: Mastodon requests the wrong url when it needs this but we compansate with a redirect to
					 * this in our ActPubController. We tolerate Mastodon breaking spec here.
					 */
					.put(APObj.url, host + "/u/" + userName + "/home") //
					.put(APObj.endpoints, new APObj().put(APObj.sharedInbox, host + APConst.PATH_INBOX)) //

					.put(APObj.publicKey, new APObj() //
							.put(APObj.id, apStr(actor, APObj.id) + "#main-key") //
							.put(APObj.owner, apStr(actor, APObj.id)) //
							.put(APObj.publicKeyPem, "-----BEGIN PUBLIC KEY-----\n" + publicKey + "\n-----END PUBLIC KEY-----\n")) //

					.put(APObj.supportsFriendRequests, true);

			// apLog.trace("Reply with Actor: " + XString.prettyPrint(actor));
			return actor;
		} catch (Exception e) {
			log.error("actor query failed", e);
			throw new RuntimeException(e);
		}
	}

}
