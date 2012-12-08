package com.shadworld.poolserver.servlet;

import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.servlets.CloseableDoSFilter;

import com.shadworld.poolserver.WorkerProxy;
import com.shadworld.poolserver.conf.Res;
import com.shadworld.poolserver.entity.Worker;

public class PsjDosFilter extends CloseableDoSFilter {

	final WorkerProxy proxy;
	
	public PsjDosFilter(WorkerProxy proxy) {
		this.proxy = proxy;
		//this.setDelayMs(10);
		this.setInsertHeaders(false);
		this.setRemotePort(true);
		this.setTrackSessions(false);
		
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jetty.servlets.DoSFilter#getMaxPriority()
	 */
	@Override
	protected int getMaxPriority() {
		// TODO Auto-generated method stub
		return super.getMaxPriority();
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jetty.servlets.DoSFilter#extractUserId(javax.servlet.ServletRequest)
	 */
	@Override
	protected String extractUserId(ServletRequest request) {
		HttpServletRequest req = (HttpServletRequest) request;
		String auth = req.getHeader("Authorization");
		if (auth == null)
			return null;
		String[] parts = Res.decodeAuthString(auth);
		if (parts == null)
			return null;
		Worker worker = proxy.getWorker(parts[0]);
		if (worker == null || worker.isBadWorker() || !worker.isLoggedIn())
			return null;
		return worker.workDoneByWorker() > 0 ? worker.getUsername() : null;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jetty.servlets.DoSFilter#initWhitelist()
	 */
	@Override
	protected void initWhitelist() {
		// TODO Auto-generated method stub
		super.initWhitelist();
	}
	
	

}
