package au.org.massive.strudel_web;

import java.util.HashSet;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import au.org.massive.strudel_web.ssh.CertAuthInfo;
import au.org.massive.strudel_web.vnc.GuacamoleSession;

/**
 * An abstraction from the {@link HttpSession} object, mediating access to session attributes
 * @author jrigby
 *
 */
public class Session {
	
	private HttpSession session;
	
	public Session(String sessionId) throws NoSuchSessionException {
		session = SessionManager.getSessionById(sessionId);
		if (session == null) {
			throw new NoSuchSessionException();
		}
	}
	
	public Session(HttpSession session) {
		this.session = session;
	}
	
	public Session(HttpServletRequest request) {
		this(request.getSession());
	}
	
	public HttpSession getHttpSession() {
		return session;
	}
	
	public String getSessionId() {
		return session.getId();
	}
	
	// Session attribute keys
	private static final String USER_EMAIL = "user-email";
	private static final String KEY_CERT = "ssh-certificate";
	private static final String OAUTH_ACCESS_TOKEN = "oauth-access-token";
	private static final String GUAC_SESSION = "guacamole-session";
	
	public void setUserEmail(String email) {
		session.setAttribute(USER_EMAIL, email);
	}
	public String getUserEmail() {
		return (String) session.getAttribute(USER_EMAIL);
	}
	public boolean hasUserEmail() {
		return getUserEmail() != null;
	}
	
	public void setCertificate(CertAuthInfo cert) {
		session.setAttribute(KEY_CERT, cert);
	}
	public CertAuthInfo getCertificate() {
		return (CertAuthInfo) session.getAttribute(KEY_CERT);
	}
	public boolean hasCertificate() {
		return getCertificate() != null;
	}
	
	public void setOAuthAccessToken(String accessToken) {
		session.setAttribute(OAUTH_ACCESS_TOKEN, accessToken);
	}
	public String getOAuthAccessToken() {
		return (String) session.getAttribute(OAUTH_ACCESS_TOKEN);
	}
	public boolean hasOAuthAccessToken() {
		return session.getAttribute(OAUTH_ACCESS_TOKEN) != null;
	}
	public void clearOAuthAccessToken() {
		session.removeAttribute(OAUTH_ACCESS_TOKEN);
	}
	
	@SuppressWarnings("unchecked")
	public Set<GuacamoleSession> getGuacamoleSessionsSet() {
		Set<GuacamoleSession> guacamoleSessions = (Set<GuacamoleSession>) session.getAttribute(GUAC_SESSION);
		if (session.getAttribute(GUAC_SESSION) == null) {
			guacamoleSessions = new HashSet<GuacamoleSession>();
			session.setAttribute(GUAC_SESSION, guacamoleSessions);
		}
		return guacamoleSessions;
	}
	
	@Override
	public boolean equals(Object o) {
		return getSessionId().equals(o);
	}
	
	@Override
	public int hashCode() {
		return getSessionId().hashCode();
	}
}