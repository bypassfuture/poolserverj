package com.shadworld.poolserver.servlet;

import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.servlets.QoSFilter;

import com.shadworld.poolserver.PoolServer;
import com.shadworld.poolserver.WorkerProxy;
import com.shadworld.poolserver.conf.Res;
import com.shadworld.poolserver.entity.Worker;

public class PsjQosFilter extends QoSFilter {

	final WorkerProxy proxy;
	
	public PsjQosFilter(WorkerProxy proxy) {
		this.proxy = proxy;
	}
	
	private boolean debugPaused = false;

	/* (non-Javadoc)
	 * @see org.eclipse.jetty.servlets.QoSFilter#getPriority(javax.servlet.ServletRequest)
	 */
	@Override
	protected int getPriority(ServletRequest request) {

		if (!debugPaused) {
			debugPaused = true;
			debugPaused = true;
		}
		
		HttpServletRequest req = (HttpServletRequest) request;
		
		String auth = req.getHeader("Authorization");
		if (auth == null)
			return 2;
		String[] parts = Res.decodeAuthString(auth);
		if (parts == null)
			return 1;
		// found so get worker from cache;
		Worker worker = proxy.getWorker(parts[0]);
		//if (Res.isTrace(Res.TRACE_LP_SPAM))
		//		worker.registerQoSPriorityCheck(PoolServer.get().getBlockTracker().getCurrentBlock());
		if (worker == null || worker.isBadWorker())
			return 0;
		if (worker.isLoggedIn()) {
			int priority = worker.workDoneByWorker() > 1 ? 4 : 3;
			priority += worker.workDoneByWorker() / 10;
			return priority > 10 ? 10 : priority;
		} else {
			return worker.getBadLoginCount() > 2 ? 0 : 1; 
		}
	}
	
	

}
