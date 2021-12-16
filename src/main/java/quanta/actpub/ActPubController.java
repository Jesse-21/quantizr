package quanta.actpub;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import quanta.actpub.model.APObj;
import quanta.config.AppProp;

import quanta.util.XString;
import static quanta.util.Util.*;

// @CrossOrigin --> Access-Control-Allow-Credentials

/**
 * Main REST Controller endpoint for AP
 */
@Controller
// @CrossOrigin (done by AppFilter. Spring doesn't do it.)
public class ActPubController  {
	private static final Logger log = LoggerFactory.getLogger(ActPubController.class);

	@Autowired
	@Lazy
	protected ActPubUtil apUtil;

	@Autowired
	@Lazy
	protected ActPubOutbox apOutbox;

	@Autowired
	@Lazy
	protected ActPubFollower apFollower;

	@Autowired
	@Lazy
	protected ActPubFollowing apFollowing;

	@Autowired
	@Lazy
	protected ActPubService apub;

	@Autowired
	@Lazy
	protected AppProp prop;

	private static final ObjectMapper mapper = new ObjectMapper();

	// NOTE: This didn't allow unknown properties as expected but putting the
	// following in the JSON classes did:
	// @JsonIgnoreProperties(ignoreUnknown = true)
	{
		mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
	}

	/*
	 * todo-1: I'm not sure if the CHARSET version of the produces is needed here (in any of the methods
	 * below), but afaik is harmless, so I'm keeping it for now.
	 */

	/**
	 * WebFinger GET
	 */
	@RequestMapping(value = APConst.PATH_WEBFINGER, method = RequestMethod.GET, produces = { //
			APConst.CTYPE_JRD_JSON, //
			APConst.CTYPE_JRD_JSON + "; " + APConst.CHARSET //
	})
	public @ResponseBody Object webFinger(//
			@RequestParam(value = "resource", required = true) String resource) {
		apUtil.log("getWebFinger: " + resource);
		Object ret = apUtil.generateWebFinger(resource);
		if (ok(ret))
			return ret;
		return new ResponseEntity<String>(HttpStatus.NOT_FOUND);
	}

	/**
	 * Actor GET (redirect for Mastodon)
	 *
	 * Mastodon insists on using this format for the URL which is NOT what we have in our Actor object
	 * so they are breaking the spec and we tolerate it by redirecting
	 * 
	 * simple redirect from /ap/user/[userName] to /u/[userName]/home
	 * 
	 * todo-1: is this documented in the user guide about user being able to have a node named 'home'
	 * and what it means if they do? Also need to ensure this ALWAYS works especially in the AP
	 * scenarios and when user has done nothing themselves to create a 'home' node.
	 * 
	 * NOTE: This is returning the HTML of the HOME, not a JSON value
	 */
	@RequestMapping(value = "/ap/user/{userName}", method = RequestMethod.GET)
	public void mastodonGetUser(//
			@PathVariable(value = "userName", required = true) String userName, //
			HttpServletRequest req, //
			HttpServletResponse response) throws Exception {
		String url = prop.getProtocolHostAndPort() + "/u/" + userName + "/home";
		apUtil.log("Redirecting to: " + url);
		response.sendRedirect(url);
	}

	/**
	 * Actor GET
	 */
	@RequestMapping(value = APConst.ACTOR_PATH + "/{userName}", method = RequestMethod.GET, produces = { //
			APConst.CTYPE_ACT_JSON, //
			APConst.CTYPE_ACT_JSON + "; " + APConst.CHARSET, //
			APConst.CTYPE_LD_JSON + "; " + APConst.APS_PROFILE //
	})
	public @ResponseBody Object actor(//
			@PathVariable(value = "userName", required = true) String userName) {
		apUtil.log("getActor: " + userName);
		Object ret = apub.generateActor(userName);
		if (ok(ret)) {
			// This pattern is needed to ENSURE a specific content type.
			HttpHeaders hdr = new HttpHeaders();
			hdr.setContentType(APConst.MTYPE_ACT_JSON);
			return new ResponseEntity<Object>(ret, hdr, HttpStatus.OK);
		}

		return new ResponseEntity<String>(HttpStatus.NOT_FOUND);
	}

	/**
	 * Shared Inbox POST
	 */
	@RequestMapping(value = APConst.PATH_INBOX, method = RequestMethod.POST, produces = {//
			APConst.CTYPE_LD_JSON, //
			APConst.CTYPE_LD_JSON + "; " + APConst.CHARSET, //
			APConst.CTYPE_LD_JSON + "; " + APConst.APS_PROFILE //
	})
	public @ResponseBody Object sharedInboxPost(//
			@RequestBody String body, //
			HttpServletRequest httpReq) {
		try {
			APObj payload = mapper.readValue(body, new TypeReference<>() {});
			apUtil.log("shared INBOX incoming payload: " + XString.prettyPrint(payload));
			ActPubService.inboxCount++;
			apub.processInboxPost(httpReq, payload);
			return new ResponseEntity<String>(HttpStatus.OK);
		} catch (Exception e) {
			return new ResponseEntity<String>(HttpStatus.BAD_REQUEST);
		}
	}

	/**
	 * User Inbox POST
	 * 
	 * WARNING: This inbox and the Shared inbox (above) can both be called simultaneously in cases when
	 * someone is doing a public reply to a Quanta node, and so Mastodon sends out the public inbox post
	 * and the post to the user simultaneously. We have ActPubService#inboxLock for being sure this
	 * won't cause duplicate records.
	 */
	@RequestMapping(value = APConst.PATH_INBOX + "/{userName}", method = RequestMethod.POST, produces = { //
			APConst.CTYPE_LD_JSON, //
			APConst.CTYPE_LD_JSON + "; " + APConst.CHARSET, //
			APConst.CTYPE_LD_JSON + "; " + APConst.APS_PROFILE //
	})
	public @ResponseBody Object inboxPost(//
			@RequestBody String body, //
			@PathVariable(value = "userName", required = true) String userName, //
			HttpServletRequest httpReq) {
		try {
			APObj payload = mapper.readValue(body, new TypeReference<>() {});
			apUtil.log("AP INBOX incoming: " + XString.prettyPrint(payload));
			ActPubService.inboxCount++;
			apub.processInboxPost(httpReq, payload);
			return new ResponseEntity<String>(HttpStatus.OK);
		} catch (Exception e) {
			return new ResponseEntity<String>(HttpStatus.BAD_REQUEST);
		}
	}

	/**
	 * Outbox GET
	 */
	@RequestMapping(value = APConst.PATH_OUTBOX + "/{userName}", method = RequestMethod.GET, produces = { //
			APConst.CTYPE_ACT_JSON, //
			APConst.CTYPE_ACT_JSON + "; " + APConst.CHARSET, //
			APConst.CTYPE_LD_JSON + "; " + APConst.APS_PROFILE //
	})
	public @ResponseBody Object outbox(//
			@PathVariable(value = "userName", required = true) String userName,
			@RequestParam(value = "min_id", required = false) String minId,
			@RequestParam(value = "page", required = false) String page) {
		APObj ret = null;
		if (APConst.TRUE.equals(page)) {
			ret = apOutbox.generateOutboxPage(userName, minId);
		} else {
			/*
			 * Mastodon calls this method, but never calls back in (to generateOutboxPage above) for any pages.
			 * I'm not sure if this is something we're doing wrong or what, because I don't know enough about
			 * what Mastodon is "supposed" to do, to be able to even say if this is incorrect or not.
			 * 
			 * From analyzing other 'server to server' calls on other Mastodon instances it seems like at least
			 * the "toot count" should be showing up, but when I search a local user (non-federated) and it gets
			 * the outbox, mastodon still shows "0 toots", even though it just queried my inbox and there ARE
			 * toots and we DID return the correct number of them.
			 */
			ret = apOutbox.generateOutbox(userName);
		}
		if (ok(ret)) {
			apUtil.log("Reply with Outbox: " + XString.prettyPrint(ret));
			return ret;
		}
		return new ResponseEntity<String>(HttpStatus.OK);
	}

	/**
	 * Followers GET
	 */
	@RequestMapping(value = APConst.PATH_FOLLOWERS + "/{userName}", method = RequestMethod.GET, produces = { //
			APConst.CTYPE_ACT_JSON, //
			APConst.CTYPE_ACT_JSON + "; " + APConst.CHARSET, //
			APConst.CTYPE_LD_JSON + "; " + APConst.APS_PROFILE //
	})
	public @ResponseBody Object getFollowers(//
			@PathVariable(value = "userName", required = false) String userName,
			@RequestParam(value = "min_id", required = false) String minId,
			@RequestParam(value = "page", required = false) String page) {
		APObj ret = null;
		if (APConst.TRUE.equals(page)) {
			ret = apFollower.generateFollowersPage(userName, minId);
		} else {
			ret = apFollower.generateFollowers(userName);
		}
		if (ok(ret)) {
			apUtil.log("Reply with Followers: " + XString.prettyPrint(ret));
			return ret;
		}
		return new ResponseEntity<String>(HttpStatus.OK);
	}

	/**
	 * Following GET
	 */
	@RequestMapping(value = APConst.PATH_FOLLOWING + "/{userName}", method = RequestMethod.GET, produces = { //
			APConst.CTYPE_ACT_JSON, //
			APConst.CTYPE_ACT_JSON + "; " + APConst.CHARSET, //
			APConst.CTYPE_LD_JSON + "; " + APConst.APS_PROFILE //
	})
	public @ResponseBody Object getFollowing(//
			@PathVariable(value = "userName", required = false) String userName,
			@RequestParam(value = "min_id", required = false) String minId,
			@RequestParam(value = "page", required = false) String page) {
		APObj ret = null;
		if (APConst.TRUE.equals(page)) {
			ret = apFollowing.generateFollowingPage(userName, minId);
		} else {
			ret = apFollowing.generateFollowing(userName);
		}
		if (ok(ret)) {
			apUtil.log("Reply with Following: " + XString.prettyPrint(ret));
			return ret;
		}
		return new ResponseEntity<String>(HttpStatus.OK);
	}
}