package com.shadworld.poolserver.logging;

import java.io.IOException;
import java.util.Date;

import org.json.JSONException;

import com.shadworld.jsonrpc.JsonRpcResponse;
import com.shadworld.poolserver.BlockChainTracker;
import com.shadworld.poolserver.PSJExchange;
import com.shadworld.poolserver.TempLogger;
import com.shadworld.poolserver.conf.Res;
import com.shadworld.poolserver.source.daemonhandler.DaemonHandler;

public class ShareExchange extends PSJExchange {
	/**
	 * 
	 */
	private final BlockChainTracker blockTracker;
	private final DaemonHandler handler;
	ShareSubmitRequest request;
	ShareEntry entry;

	public ShareExchange(ShareSubmitter workResultSubmitter, BlockChainTracker blockTracker, ShareSubmitRequest request) {
		super();
		entry = request.getShareEntry();
		//this.workResultSubmitter = workResultSubmitter;
		this.handler = entry.source.getDaemonHandler();
		//this.thread = thread;
		this.request = request;
		this.entry = request.getShareEntry();
		this.submitterOwner = workResultSubmitter;
		this.blockTracker = blockTracker;
	}
	
	public void reset(ShareEntry entry) {
		super.reset(null);
		this.entry = entry;
	}

//	private void logBadResult(String reason, Throwable t, boolean retry) {
//		if (retry && entry.getUpstreamSubmitFails() < this.workResultSubmitter.maxSubmitRetryOnConnectionFail) {
//			entry.setUpstreamSubmitFails(entry.getUpstreamSubmitFails() + 1);
//			this.workResultSubmitter.submitUpstream(entry);
//			return;
//		}
//		entry.setException(t);
//		entry.reason = reason;
//		entry.upstreamResult = false;
//		this.workResultSubmitter.shareLogger.submitLogEntry(entry);
//		
//		if (entry.isRealSolution) {
//			TempLogger.logRealShare(new Date() + " - failed sending real solution:           " + entry.solution + " reason: " + entry.reason);
//			if (t != null) {
//				TempLogger.logRealShare(t.getMessage());
//			}
//		}
//		
//	}

//	private void releaseShutdownLock() {
//		synchronized (this.workResultSubmitter.finalRequests) {
//			this.workResultSubmitter.finalRequests.remove(this);
//			if (this.workResultSubmitter.finalRequests.size() == 0 && this.workResultSubmitter.finalRequestSubmitted) {
//				this.workResultSubmitter.finalRequests.notifyAll();
//				synchronized (this.workResultSubmitter.shutdownLock) {
//					this.workResultSubmitter.shutdownLock.notifyAll();
//				}
//			}
//		}
//	}

	public void onResponseComplete() throws IOException {
		super.onResponseComplete();
		String responseString = this.getResponseContent();
		try {
			JsonRpcResponse response = new JsonRpcResponse(responseString, null);
			handler.onShareSubmitComplete(entry, response.getJSONObject().getBoolean("result"));
//			entry.upstreamResult = response.getJSONObject().getBoolean("result");
//			this.workResultSubmitter.shareLogger.submitLogEntry(entry);
//			Res.logDebug("work submit success, result: " + entry.upstreamResult);
//			if (entry.upstreamResult) {
//				blockTracker.fireBlockChange(entry.source, true);
//				try {
//					Res.logInfo(new Date() + "FOUND BLOCK - Worker: " + entry.worker.getUsername() + " Source: " + entry.source.getName() + " Solution: " + entry.solution);
//				} catch (Throwable t) {}
//				
//			}
//			if (entry.isRealSolution) {
//				TempLogger.logRealShare(new Date() + " - finished sending real solution:           " + entry.solution + " - Upstream result:           " + entry.upstreamResult);
//			} else if (entry.upstreamResult) {
//				TempLogger.logRealShare(new Date() + " - upstream accepted solution but we didn't detect it! : " + entry.solution);
//			}
		} catch (JSONException e) {
			Res.logError("InvalidJsonResponse", e);
			handler.onShareSubmitFail(request, "invalid-json-from-upstream", e, false);
			//logBadResult("invalid-json-from-upstream", e, false);
			//Res.logDebug("work submit fail");
		}
	}

	@Override
	protected void onConnectionFailed(Throwable x) {
		handler.onShareSubmitFail(request, "connection-failed", x, true);
		super.onConnectionFailed(x);
//		if (this.workResultSubmitter.shutdownRequested)
//			releaseShutdownLock();
	}

	@Override
	protected void onException(Throwable x) {
		handler.onShareSubmitFail(request, "unknown-exception", x, true);
		super.onException(x);
//		if (this.workResultSubmitter.shutdownRequested)
//			releaseShutdownLock();
	}

	@Override
	protected void onExpire() {
		handler.onShareSubmitFail(request, "connection-timeout", null, true);
		super.onExpire();
//		if (this.workResultSubmitter.shutdownRequested)
//			releaseShutdownLock();
	}
}