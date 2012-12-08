package com.shadworld.poolserver.servlet.auth;

import javax.servlet.http.HttpServletRequest;

import com.shadworld.poolserver.WorkerProxy;
import com.shadworld.poolserver.conf.Res;
import com.shadworld.poolserver.entity.Worker;
import com.shadworld.poolserver.servlet.auth.WorkerLoginEntry.LoginResult;
import com.shadworld.utils.StringTools;

/**
 * NOTE: if subclassing this with your own custom autheticator you must provide a constructor with the same
 * signature as this one i.e. 
 * public MyAuthenticator(WorkerProxy workerProxy) {
 *		super(workerProxy);
 *	}
 * @author git
 *
 */
public class WorkerAuthenticator {

	WorkerProxy workerProxy;
	
	public WorkerAuthenticator(WorkerProxy workerProxy, String[] extraParams) {
		super();
		Res.logDebug("Building Worker-authentication-engine: " + getClass().getSimpleName() + ".class with extraParams: " + (extraParams == null ? null : "[" + StringTools.concatStrings(extraParams, ",") + "]"));
		this.workerProxy = workerProxy;
	}


	/**
	 * check authorization and return relevant worker object if auth ok.
	 * Must set WorkerLoginEntry.loginResult
	 * 
	 * @param req
	 * @return
	 */
	public WorkerLoginEntry checkAuth(HttpServletRequest req) {
		// extract username and pass from header
		String username;
		String password;
		String auth = req.getHeader("Authorization");
		if (auth == null)
			return null;
		String[] parts = Res.decodeAuthString(auth);
		if (parts == null)
			return null;
		// found so get worker from cache;
		Worker worker = workerProxy.getWorker(parts[0]);
		if (worker != null) {
			WorkerLoginEntry entry = new WorkerLoginEntry();
			entry.ip = getRemoteIpAddress(req);
			entry.worker = worker;
			entry.suppliedPassword = parts[1];
			
			if (entry == null) {
				return null;
			}
			if (worker.isBadWorker()) {
				entry.loginResult = LoginResult.WORKER_NOT_FOUND;
			} else if (!entry.worker.isAllowedHost(entry.ip)) {
				entry.loginResult = LoginResult.DISALLOWED_HOST;
			} else if (!passwordMatch(entry.worker.getPassword(), parts[1])) {
				entry.loginResult = LoginResult.BAD_PASSWORD;
			} else {
				entry.loginResult = LoginResult.OK;
				entry.worker.setLoggedIn(true);
			}
			return entry;
		}
		return null;
	}
	
	/**
	 * returns the real ip address of the remote host.  If the X-Forwarded-For header is present
	 * that ip is returned if not then the usual remoteAddr.
	 * @param req
	 * @return
	 */
	public String getRemoteIpAddress(HttpServletRequest req) {
		String ip = req.getHeader("X-Forwarded-For");
		if (ip == null)
			ip = req.getRemoteAddr();
		return ip;
	}
	
	/**
	 * override this method for different password matching schemes (e.g. encrypted passwords).
	 * to disable authentication simple return true;
	 * @param password
	 * @param suppliedPassword
	 * @return
	 */
	protected boolean passwordMatch(String password, String suppliedPassword) {
		return password != null && password.equals(suppliedPassword);
	}

	
	/**
	 * called when worker successfully retrieved from cache
	 */
	public void onWorkerCacheHit() {
	}
	
	/**
	 * called when unknown worker successfully retrieved from cache
	 */
	public void onBadWorkerCacheHit() {
	}
	
}
