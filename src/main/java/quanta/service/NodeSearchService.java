package quanta.service;

import static quanta.util.Util.no;
import static quanta.util.Util.ok;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import org.apache.commons.lang3.StringUtils;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.TextCriteria;
import org.springframework.stereotype.Component;
import quanta.config.NodePath;
import quanta.config.ServiceBase;
import quanta.instrument.PerfMon;
import quanta.instrument.PerfMonEvent;
import quanta.model.NodeInfo;
import quanta.model.PropertyInfo;
import quanta.model.client.Bookmark;
import quanta.model.client.Constant;
import quanta.model.client.ConstantInt;
import quanta.model.client.NodeProp;
import quanta.model.client.NodeType;
import quanta.model.client.PrincipalName;
import quanta.model.client.PrivilegeType;
import quanta.mongo.MongoSession;
import quanta.mongo.MongoUtil;
import quanta.mongo.model.AccessControl;
import quanta.mongo.model.SubNode;
import quanta.request.GetBookmarksRequest;
import quanta.request.GetNodeStatsRequest;
import quanta.request.GetSharedNodesRequest;
import quanta.request.NodeSearchRequest;
import quanta.request.RenderDocumentRequest;
import quanta.response.GetBookmarksResponse;
import quanta.response.GetNodeStatsResponse;
import quanta.response.GetSharedNodesResponse;
import quanta.response.NodeSearchResponse;
import quanta.response.RenderDocumentResponse;
import quanta.util.ExUtil;
import quanta.util.ThreadLocals;
import quanta.util.Val;
import quanta.util.XString;

/**
 * Service for searching the repository. This searching is currently very basic, and just grabs the
 * first 100 results. Despite it being basic right now, it is however EXTREMELY high performance and
 * leverages the full and best search performance that can be gotten out of Lucene, which beats any
 * other technology in the world in it's power.
 * 
 * NOTE: the Query class DOES have a 'skip' and 'limit' which I can take advantage of in all my
 * searching but I'm not fully doing so yet I don't believe.
 */
@Component
public class NodeSearchService extends ServiceBase {
	private static final Logger log = LoggerFactory.getLogger(NodeSearchService.class);

	public static Object trendingFeedInfoLock = new Object();
	public static GetNodeStatsResponse trendingFeedInfo;

	static final String SENTENCE_DELIMS = ".!?";

	/*
	 * Warning: Do not add '#' or '@' to this list becasue we're using it to parse text for hashtags
	 * and/or usernames so those characters are part of the text. Also since urls sometimes contain
	 * something like "/path/#hash=" where a hashtag is used as a parameter in the url we also don't
	 * want / or ? or & characters in this delimiters list, and to support hyphenated terms we don't
	 * want '-' character as a delimiter either
	 */
	static final String WORD_DELIMS = " \n\r\t,;:\"'`()*{}[]<>=\\.!“";

	static final int TRENDING_LIMIT = 10000;

	@PerfMon(category = "search")
	public RenderDocumentResponse renderDocument(MongoSession ms, RenderDocumentRequest req) {
		RenderDocumentResponse res = new RenderDocumentResponse();
		PerfMonEvent perf = new PerfMonEvent(0, null, ms.getUserName());

		List<NodeInfo> results = new LinkedList<>();
		res.setSearchResults(results);

		SubNode node = read.getNode(ms, new ObjectId(req.getRootId()));
		if (!ok(node)) {
			return res;
		}

		perf.chain("rendDoc:GetNode");

		boolean adminOnly = acl.isAdminOwned(node);

		HashSet<String> truncates = new HashSet<>();
		List<SubNode> nodes = read.genDocList(ms, req.getRootId(), req.getStartNodeId(), req.isIncludeComments(), truncates);
		int counter = 0;

		perf.chain("rendDoc:getDocList");

		for (SubNode n : nodes) {
			NodeInfo info = convert.convertToNodeInfo(adminOnly, ThreadLocals.getSC(), ms, n, false, counter + 1, false, false,
					false, false, false, true, null, false);

			if (ok(info)) {
				if (truncates.contains(n.getIdStr())) {
					info.safeGetClientProps().add(new PropertyInfo(NodeProp.TRUNCATED.s(), "t"));
				}

				results.add(info);
			}
			perf.chain("rendDoc:converted");
		}
		return res;
	}

	public NodeSearchResponse search(MongoSession ms, NodeSearchRequest req) {
		NodeSearchResponse res = new NodeSearchResponse();
		String searchText = req.getSearchText();

		// if no search text OR sort order specified that's a bad request.
		if (StringUtils.isEmpty(searchText) && //
				StringUtils.isEmpty(req.getSearchType()) && //
				// note: for timelines this is called but with a sort
				StringUtils.isEmpty(req.getSortField())) {
			throw new RuntimeException("Search text or ordering required.");
		}

		List<NodeInfo> searchResults = new LinkedList<>();
		res.setSearchResults(searchResults);
		int counter = 0;

		if ("node.id".equals(req.getSearchProp())) {
			SubNode node = read.getNode(ms, searchText, true, null);
			if (ok(node)) {
				NodeInfo info = convert.convertToNodeInfo(false, ThreadLocals.getSC(), ms, node, false, counter + 1, false, false,
						false, false, false, true, null, false);
				if (ok(info)) {
					searchResults.add(info);
				}
			}
		} else if ("node.name".equals(req.getSearchProp())) {
			/* Undocumented Feature: You can find named nodes using format ":userName:nodeName" */
			if (!searchText.contains(":")) {
				if (ThreadLocals.getSC().isAdmin()) {
					searchText = ":" + searchText;
				} else {
					searchText = ":" + ThreadLocals.getSC().getUserName() + ":" + searchText;
				}
			}
			SubNode node = read.getNode(ms, searchText, true, null);
			if (ok(node)) {
				NodeInfo info = convert.convertToNodeInfo(false, ThreadLocals.getSC(), ms, node, false, counter + 1, false, false,
						false, false, false, true, null, false);
				if (ok(info)) {
					searchResults.add(info);
				}
			}
		}
		// othwerwise we're searching all node properties
		else {
			/* USER Search */
			if (Constant.SEARCH_TYPE_USER_FOREIGN.s().equals(req.getSearchType()) || //
					Constant.SEARCH_TYPE_USER_LOCAL.s().equals(req.getSearchType()) || //
					Constant.SEARCH_TYPE_USER_ALL.s().equals(req.getSearchType())) {
				userSearch(ms, null, req, searchResults);
			}
			// else we're doing a normal subgraph search for the text
			else {
				SubNode searchRoot = null;

				// todo-1: make this 'allNodes' a constant
				if ("allNodes".equals(req.getSearchRoot())) {
					searchRoot = read.getNode(ms, ThreadLocals.getSC().getRootId());
				} else {
					searchRoot = read.getNode(ms, req.getNodeId());
				}
				boolean adminOnly = acl.isAdminOwned(searchRoot);

				if ("timeline".equals(req.getSearchDefinition())) {
					ThreadLocals.getSC().setTimelinePath(searchRoot.getPath());
				}

				for (SubNode node : read.searchSubGraph(ms, searchRoot, req.getSearchProp(), searchText, req.getSortField(),
						req.getSortDir(), ConstantInt.ROWS_PER_PAGE.val(), ConstantInt.ROWS_PER_PAGE.val() * req.getPage(),
						req.getFuzzy(), req.getCaseSensitive(), req.getTimeRangeType(), req.isRecursive(),
						req.isRequirePriority(), req.isRequireAttachment())) {
					try {
						NodeInfo info = convert.convertToNodeInfo(adminOnly, ThreadLocals.getSC(), ms, node, false, counter + 1,
								false, false, false, false, false, true, null, false);
						if (ok(info)) {
							searchResults.add(info);
						}
					} catch (Exception e) {
						ExUtil.error(log, "Failed converting node", e);
					}
				}
			}
		}

		res.setSuccess(true);
		return res;
	}

	private void userSearch(MongoSession ms, String userDoingAction, NodeSearchRequest req, List<NodeInfo> searchResults) {
		String findUserName = null;
		int counter = 0;

		TextCriteria textCriteria = null;
		if (!StringUtils.isEmpty(req.getSearchText())) {
			findUserName = req.getSearchText();
			textCriteria = TextCriteria.forDefaultLanguage();

			// make sure name is quoted for exact search, since it will contain delimiters
			// which would other wise mess up a search
			String name = findUserName;
			if (!name.startsWith("\"")) {
				name = "\"" + name;
			}
			if (!name.endsWith("\"")) {
				name = name + "\"";
			}
			textCriteria.matching(name);
			textCriteria.caseSensitive(req.getCaseSensitive());
		}


		Val<Iterable<SubNode>> accountNodes = new Val<>();;
		final TextCriteria _textCriteria = textCriteria;

		// Run this as admin because ordinary users don't have access to account nodes.
		arun.run(as -> {
			accountNodes.setVal(read.getAccountNodes(as, _textCriteria, null, //
					ConstantInt.ROWS_PER_PAGE.val(), //
					ConstantInt.ROWS_PER_PAGE.val() * req.getPage(), //
					Constant.SEARCH_TYPE_USER_FOREIGN.s().equals(req.getSearchType()), //
					Constant.SEARCH_TYPE_USER_LOCAL.s().equals(req.getSearchType())));

			return null;
		});

		if (ok(accountNodes.getVal())) {
			/*
			 * scan all userAccountNodes, and set a zero amount for those not found (which will be the correct
			 * amount).
			 */
			for (SubNode node : accountNodes.getVal()) {
				try {
					NodeInfo info = convert.convertToNodeInfo(false, ThreadLocals.getSC(), ms, node, false, counter + 1, false,
							false, false, false, false, false, null, false);
					if (ok(info)) {
						searchResults.add(info);
					}
				} catch (Exception e) {
					ExUtil.error(log, "failed converting user node", e);
				}
			}
		}

		/*
		 * If we didn't find any results and we aren't searching locally only then try to look this up as a
		 * username
		 */
		if (searchResults.size() == 0 && !Constant.SEARCH_TYPE_USER_LOCAL.s().equals(req.getSearchType())) {
			findUserName = findUserName.replace("\"", "");
			findUserName = XString.stripIfStartsWith(findUserName, "@");
			final String _findUserName = findUserName;
			arun.run(as -> {
				SubNode userNode = apub.getAcctNodeByForeignUserName(as, userDoingAction, _findUserName, false, true);
				if (ok(userNode)) {
					try {
						NodeInfo info = convert.convertToNodeInfo(false, ThreadLocals.getSC(), as, userNode, false, counter + 1,
								false, false, false, false, false, false, null, false);
						if (ok(info)) {
							searchResults.add(info);
						}
					} catch (Exception e) {
						ExUtil.error(log, "failed converting user node", e);
					}
				}
				return null;
			});
		}
	}

	public GetSharedNodesResponse getSharedNodes(MongoSession ms, GetSharedNodesRequest req) {
		GetSharedNodesResponse res = new GetSharedNodesResponse();
		ms = ThreadLocals.ensure(ms);
		List<NodeInfo> searchResults = new LinkedList<>();
		res.setSearchResults(searchResults);
		int counter = 0;

		/*
		 * DO NOT DELETE (may want searching under selected node as an option some day) we can remove nodeId
		 * from req, because we always search from account root now.
		 */
		// SubNode searchRoot = api.getNode(session, req.getNodeId());

		// search under account root only
		SubNode searchRoot = read.getNode(ms, ThreadLocals.getSC().getRootId());

		/*
		 * todo-2: Eventually we want two ways of searching here.
		 * 
		 * 1) All my shared nodes under my account,
		 * 
		 * 2) all my shared nodes globally, and the globally is done simply by passing null for the path
		 * here
		 */
		for (SubNode node : auth.searchSubGraphByAcl(ms, req.getPage() * ConstantInt.ROWS_PER_PAGE.val(), searchRoot.getPath(),
				searchRoot.getOwner(), Sort.by(Sort.Direction.DESC, SubNode.MODIFY_TIME), ConstantInt.ROWS_PER_PAGE.val())) {

			if (no(node.getAc()) || node.getAc().size() == 0)
				continue;

			/*
			 * If we're only looking for shares to a specific person (or public) then check here
			 */
			if (ok(req.getShareTarget())) {

				if (!node.getAc().containsKey(req.getShareTarget())) {
					continue;
				}

				// if specifically searching for rd or wr
				if (ok(req.getAccessOption())) {
					AccessControl ac = node.getAc().get(req.getShareTarget());

					// log.debug("NodeId: " + node.getIdStr() + " req=" + req.getAccessOption() + " privs="
					// + ac.getPrvs());
					if (req.getAccessOption().contains(PrivilegeType.READ.s()) && //
							(!ac.getPrvs().contains(PrivilegeType.READ.s()) || //
									ac.getPrvs().contains(PrivilegeType.WRITE.s()))) {
						continue;
					}
					if (req.getAccessOption().contains(PrivilegeType.WRITE.s())
							&& !ac.getPrvs().contains(PrivilegeType.WRITE.s())) {
						continue;
					}
				}
			}

			NodeInfo info = convert.convertToNodeInfo(false, ThreadLocals.getSC(), ms, node, false, counter + 1, false, false,
					false, false, false, true, null, false);
			if (ok(info)) {
				searchResults.add(info);
			}
		}

		res.setSuccess(true);
		// log.debug("search results count: " + counter);
		return res;
	}

	public void getBookmarks(MongoSession ms, GetBookmarksRequest req, GetBookmarksResponse res) {
		List<Bookmark> bookmarks = new LinkedList<>();

		List<SubNode> bookmarksNode = user.getSpecialNodesList(ms, null, NodeType.BOOKMARK_LIST.s(), null, true, null);
		if (ok(bookmarksNode)) {
			for (SubNode bmNode : bookmarksNode) {
				String targetId = bmNode.getStr(NodeProp.TARGET_ID);
				Bookmark bm = new Bookmark();
				String shortContent = render.getFirstLineAbbreviation(bmNode.getContent(), 100);
				bm.setName(shortContent);
				bm.setId(targetId);
				bm.setSelfId(bmNode.getIdStr());
				bookmarks.add(bm);
			}
		}

		res.setSuccess(true);
		res.setBookmarks(bookmarks);
	}

	public void getNodeStats(MongoSession ms, GetNodeStatsRequest req, GetNodeStatsResponse res) {
		boolean countVotes = !req.isFeed();

		/*
		 * If this is the 'feed' being queried (i.e. the Trending tab on the app), then get the data from
		 * trendingFeedInfo (the cache), or else cache it
		 */
		synchronized (NodeSearchService.trendingFeedInfoLock) {
			if (req.isFeed() && ok(NodeSearchService.trendingFeedInfo)) {
				res.setStats(NodeSearchService.trendingFeedInfo.getStats());
				res.setTopMentions(NodeSearchService.trendingFeedInfo.getTopMentions());
				res.setTopTags(NodeSearchService.trendingFeedInfo.getTopTags());
				res.setTopWords(NodeSearchService.trendingFeedInfo.getTopWords());
				res.setSuccess(true);
				return;
			}
		}

		HashMap<String, WordStats> wordMap = req.isGetWords() ? new HashMap<>() : null;
		HashMap<String, WordStats> tagMap = req.isGetTags() ? new HashMap<>() : null;
		HashMap<String, WordStats> mentionMap = req.isGetMentions() ? new HashMap<>() : null;
		HashMap<String, WordStats> voteMap = countVotes ? new HashMap<>() : null;

		long nodeCount = 0;
		long totalWords = 0;
		Iterable<SubNode> iter = null;
		boolean strictFiltering = false;

		/*
		 * NOTE: This query is similar to the one in UserFeedService.java, but simpler since we don't handle
		 * a bunch of options but just the public feed query
		 */
		if (req.isFeed()) {
			strictFiltering = true;
			List<Criteria> ands = new LinkedList<>();
			Query q = new Query();

			Criteria crit = Criteria.where(SubNode.PATH).regex(mongoUtil.regexRecursiveChildrenOfPath(NodePath.USERS_PATH));

			// This pattern is what is required when you have multiple conditions added to a
			// single field.
			ands.add(Criteria.where(SubNode.TYPE).ne(NodeType.FRIEND.s())); //
			ands.add(Criteria.where(SubNode.TYPE).ne(NodeType.POSTS.s())); //
			ands.add(Criteria.where(SubNode.TYPE).ne(NodeType.ACT_PUB_POSTS.s()));

			// For public feed statistics only consider PUBLIC nodes.
			ands.add(Criteria.where(SubNode.AC + "." + PrincipalName.PUBLIC.s()).ne(null));

			HashSet<ObjectId> blockedUserIds = new HashSet<>();

			/*
			 * We block the "remote users" and "local users" by blocking any admin owned nodes, but we also just
			 * want to in general for other reasons block any admin-owned nodes from showing up in feeds. Feeds
			 * are always only about user content.
			 */
			blockedUserIds.add(auth.getAdminSession().getUserNodeId());

			// filter out any nodes owned by users the admin has blocked.
			userFeed.getBlockedUserIds(blockedUserIds, PrincipalName.ADMIN.s());

			if (blockedUserIds.size() > 0) {
				ands.add(Criteria.where(SubNode.OWNER).nin(blockedUserIds));
			}

			crit.andOperator(ands);

			q.addCriteria(crit);
			q.with(Sort.by(Sort.Direction.DESC, SubNode.MODIFY_TIME));
			q.limit(TRENDING_LIMIT);

			iter = mongoUtil.find(q);
		}
		/*
		 * Otherwise this is not a Feed Tab query but just an arbitrary node stats request, like a user
		 * running a stats request under the 'Node Info' main menu
		 */
		else {
			ms = ThreadLocals.ensure(ms);
			SubNode searchRoot = read.getNode(ms, req.getNodeId());

			Sort sort = null;
			int limit = 0;
			if (req.isTrending()) {
				sort = Sort.by(Sort.Direction.DESC, SubNode.MODIFY_TIME);
				limit = TRENDING_LIMIT;
			}

			// We pass true if this is a basic subgraph (not a Trending analysis), so that running Node Stats
			// has the side effect of cleaning out orphans.
			// Note doAuth is saying here if there's any potential secrets in the results then use doAuth=true
			boolean doAuth = ok(wordMap) || ok(tagMap) || ok(mentionMap);
			iter = read.getSubGraph(ms, searchRoot, sort, limit, limit == 0 ? true : false, false, doAuth);
		}

		int publicCount = 0;
		int publicWriteCount = 0;
		int adminOwnedCount = 0;
		int userShareCount = 0;
		int signedNodeCount = 0;
		int unsignedNodeCount = 0;
		int failedSigCount = 0;

		HashSet<String> uniqueUsersSharedTo = new HashSet<>();
		HashSet<ObjectId> uniqueVoters = countVotes ? new HashSet<>() : null;

		for (SubNode node : iter) {
			nodeCount++;
			if (req.isSignatureVerify()) {
				String sig = node.getStr(NodeProp.CRYPTO_SIG);
				if (ok(sig)) {
					signedNodeCount++;
					if (!crypto.nodeSigVerify(node, sig)) {
						failedSigCount++;
					}
				} else {
					log.debug("UNSIGNED: " + XString.prettyPrint(node));
					unsignedNodeCount++;
				}
			}

			// PART 1: Process sharing info
			HashMap<String, AccessControl> aclEntry = node.getAc();
			if (ok(aclEntry)) {
				for (String key : aclEntry.keySet()) {
					AccessControl ac = aclEntry.get(key);
					if (PrincipalName.PUBLIC.s().equals(key)) {
						publicCount++;
						if (ok(ac) && ok(ac.getPrvs()) && ac.getPrvs().contains(PrivilegeType.WRITE.s())) {
							publicWriteCount++;
						}
					} else {
						userShareCount++;
						uniqueUsersSharedTo.add(key);
					}
				}
			}

			if (acl.isAdminOwned(node)) {
				adminOwnedCount++;
			}

			// PART 2: process 'content' text.
			if (no(node.getContent()))
				continue;

			String content = node.getContent();
			if (ok(node.getTags())) {
				content += " " + node.getTags();
			}

			// if strict content filtering ignore non-english or bad words posts completely
			if (strictFiltering && (!english.isEnglish(content) || english.hasBadWords(content))) {
				continue;
			}

			HashSet<String> knownTokens = null;
			StringTokenizer tokens = new StringTokenizer(content, WORD_DELIMS, false);
			while (tokens.hasMoreTokens()) {
				String token = tokens.nextToken().trim();

				if (!english.isStopWord(token)) {
					String lcToken = token.toLowerCase();

					// if word is a mention.
					if (token.startsWith("@")) {
						if (token.length() == 1)
							continue;

						// lazy create and update knownTokens
						if (no(knownTokens)) {
							knownTokens = new HashSet<>();
						}
						knownTokens.add(lcToken);

						if (ok(mentionMap)) {
							WordStats ws = mentionMap.get(lcToken);
							if (no(ws)) {
								ws = new WordStats(token);
								mentionMap.put(lcToken, ws);
							}
							ws.count++;
						}
					}
					// if word is a hashtag.
					else if (token.startsWith("#")) {
						// Tallship is google bombing my stats, so until I have a "block tags" feature
						// he's manually blocked.
						if (token.endsWith("#") || token.length() == 1 || token.toLowerCase().contains("tallship"))
							continue;

						// ignore stuff like #1 #23
						String numCheck = token.substring(1);
						if (StringUtils.isNumeric(numCheck))
							continue;

						// lazy create and update knownTokens
						if (no(knownTokens)) {
							knownTokens = new HashSet<>();
						}
						knownTokens.add(lcToken);

						if (ok(tagMap)) {
							WordStats ws = tagMap.get(lcToken);
							if (no(ws)) {
								ws = new WordStats(token);
								tagMap.put(lcToken, ws);
							}
							ws.count++;
						}
					}
					// ordinary word
					else {
						if (!StringUtils.isAlpha(token)) {
							continue;
						}

						if (ok(wordMap)) {
							WordStats ws = wordMap.get(lcToken);
							if (no(ws)) {
								ws = new WordStats(token);
								wordMap.put(lcToken, ws);
							}
							ws.count++;
						}
					}
				}
				totalWords++;
			}
			extractTagsAndMentions(node, knownTokens, tagMap, mentionMap);

			if (countVotes) {
				String vote = node.getStr(NodeProp.VOTE.s());
				if (ok(vote)) {
					// 'add' returns true if we are encountering this ID for the first time, so we can tally it's vote
					if (uniqueVoters.add(node.getId())) {
						WordStats ws = voteMap.get(vote);
						if (no(ws)) {
							ws = new WordStats(vote);
							voteMap.put(vote, ws);
						}
						ws.count++;
					}
				}
			}
		}

		List<WordStats> wordList = req.isGetWords() ? new ArrayList<>(wordMap.values()) : null;
		List<WordStats> tagList = req.isGetTags() ? new ArrayList<>(tagMap.values()) : null;
		List<WordStats> mentionList = req.isGetMentions() ? new ArrayList<>(mentionMap.values()) : null;
		List<WordStats> voteList = countVotes ? new ArrayList<>(voteMap.values()) : null;

		if (ok(wordList))
			wordList.sort((s1, s2) -> (int) (s2.count - s1.count));

		if (ok(tagList))
			tagList.sort((s1, s2) -> (int) (s2.count - s1.count));

		if (ok(mentionList))
			mentionList.sort((s1, s2) -> (int) (s2.count - s1.count));

		if (ok(voteList))
			voteList.sort((s1, s2) -> (int) (s2.count - s1.count));

		StringBuilder sb = new StringBuilder();
		sb.append("Node count: " + nodeCount + ", Total Words: " + totalWords + "\n");

		if (ok(wordList)) {
			sb.append("Unique Words: " + wordList.size() + "\n");
		}

		if (ok(voteList)) {
			sb.append("Unique Votes: " + voteList.size() + "\n");
		}

		sb.append("Public: " + publicCount + ", ");
		sb.append("Public Writable: " + publicWriteCount + "\n");
		sb.append("Admin Owned: " + adminOwnedCount + "\n");
		sb.append("User Shares: " + userShareCount + "\n");
		sb.append("Unique Users Shared To: " + uniqueUsersSharedTo.size() + "\n");

		if (req.isSignatureVerify()) {
			sb.append("Signed: " + signedNodeCount + ", Unsigned: " + unsignedNodeCount + ", FAILED SIGS: " + failedSigCount);
		}

		res.setStats(sb.toString());

		if (ok(wordList)) {
			ArrayList<String> topWords = new ArrayList<>();
			res.setTopWords(topWords);
			for (WordStats ws : wordList) {
				topWords.add(ws.word); // + "," + ws.count);
				if (topWords.size() >= 100)
					break;
			}
		}

		if (ok(voteList)) {
			ArrayList<String> topVotes = new ArrayList<>();
			res.setTopVotes(topVotes);
			for (WordStats ws : voteList) {
				topVotes.add(ws.word + "(" + ws.count + ")");
				if (topVotes.size() >= 100)
					break;
			}
		}

		if (ok(tagList)) {
			ArrayList<String> topTags = new ArrayList<>();
			res.setTopTags(topTags);
			for (WordStats ws : tagList) {
				topTags.add(ws.word); // + "," + ws.count);
				if (topTags.size() >= 100)
					break;
			}
		}

		if (ok(mentionList)) {
			ArrayList<String> topMentions = new ArrayList<>();
			res.setTopMentions(topMentions);
			for (WordStats ws : mentionList) {
				topMentions.add(ws.word); // + "," + ws.count);
				if (topMentions.size() >= 100)
					break;
			}
		}

		res.setSuccess(true);

		/*
		 * If this is a feed query cache it. Only will refresh every 30mins based on a @Schedule event
		 */
		if (req.isFeed()) {
			synchronized (NodeSearchService.trendingFeedInfoLock) {
				NodeSearchService.trendingFeedInfo = res;
			}
		}
	}

	// #tag-array
	private void extractTagsAndMentions(SubNode node, HashSet<String> knownTokens, HashMap<String, WordStats> tagMap,
			HashMap<String, WordStats> mentionMap) {
		List<Object> tags = node.getObj(NodeProp.ACT_PUB_TAG.s(), List.class);
		if (no(tags))
			return;

		for (Object tag : tags) {
			try {
				if (tag instanceof Map) {
					Map<?, ?> m = (Map) tag;
					Object type = m.get("type");
					// Object href = m.get("href");
					Object name = m.get("name");
					if (!(type instanceof String) || !(name instanceof String)) {
						continue;
					}

					String _name = ((String) name).toLowerCase();

					// we use the knownTags to avoid double counting stuff we already counted from the content text
					if (ok(knownTokens) && knownTokens.contains(_name))
						continue;

					// Mentions
					if (type.equals("Mention")) {
						/*
						 * Technically the fully qualified name would be the perfect identification for user, but to avoid
						 * double-counting names that are parset out of the content as the short (no instance) version of
						 * the name we ignore the href, in here, but href *could* be used if we needed the full name, like
						 * what we do in parseMentionsFromNode()
						 */
						WordStats ws = mentionMap.get(_name);
						if (no(ws)) {
							ws = new WordStats(_name);
							mentionMap.put(_name, ws);
						}
						ws.count++;
					}
					// Hashtags
					else if (type.equals("Hashtag")) {
						if (!_name.toLowerCase().contains("tallship")) {
							WordStats ws = tagMap.get(_name);
							if (no(ws)) {
								ws = new WordStats(_name);
								tagMap.put(_name, ws);
							}
							ws.count++;
						}
					}
				}
			} catch (Exception e) {
				// just ignore this.
			}
		}
	}
}
