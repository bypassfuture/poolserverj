package com.shadworld.poolserver.source.daemonhandler;

import java.util.Date;

import org.json.JSONException;

import com.shadworld.jsonrpc.JsonRpcResponse;
import com.shadworld.poolserver.BlockChainTracker;
import com.shadworld.poolserver.PoolServer;
import com.shadworld.poolserver.TempLogger;
import com.shadworld.poolserver.conf.Res;
import com.shadworld.poolserver.entity.Work;
import com.shadworld.poolserver.logging.ShareEntry;
import com.shadworld.poolserver.logging.ShareSubmitRequest;
import com.shadworld.poolserver.logging.ShareSubmitter;
import com.shadworld.poolserver.source.GetWorkRequest;
import com.shadworld.poolserver.source.WorkFetcherThread;
import com.shadworld.poolserver.source.WorkSource;
import com.shadworld.poolserver.stats.Stats;

public abstract class DaemonHandler {

	protected PoolServer server;

	protected WorkSource source;
	protected WorkFetcherThread fetcherThread;
	protected ShareSubmitter shareSubmitter;

	public DaemonHandler(WorkSource workSource) {
		source = workSource;
		server = PoolServer.get();
		fetcherThread = source.getWorkFetcherThread();
		shareSubmitter = server.getShareSubmitter();
	}

	
	public void init() {
		
	}
	
	public abstract void shutdown();

	public abstract void start();

	/**
	 * This is primary interface to abstract the getwork process from the WorkSource.  The implementation
	 * should use whatever underlying transport to issue an asynchronous request for work.
	 * 
	 * As the request is async the DaemonHandler must take responsibility for a number of post processing tasks
	 * once a response message is received.  
	 * <br/><br/>
	 * 1/ If it received a block number as part of the response it must call the appropriate report method on
	 * the BlockChainTracker<br/>
	 * 2/ It must call WorkFetcherThread.notifyQueue()  EVEN IF THE REQUEST FAILS;<br>
	 * 3/ It must call the appropriate stats event registrations depending on the outcome of the request e.g.<br>
	 * 		<code>workSource.getState().registerConnectFail();</code><br/>
	 * 		<code>Stats.get().registerUpstreamRequestFailed(workSource);</code><br/>
	 * 3/ It must provide a Work object and call WorkSource.processSingleResponse once for any Work it receives.<br>
	 * @param workRequest
	 */
	public abstract void doGetWork(GetWorkRequest workRequest);

	public abstract GetWorkRequest buildGetWorkRequest();
	
	/**
	 * This is primary interface to abstract the block check process from the WorkSource.  The implementation
	 * should use whatever underlying transport to issue an asynchronous request for the current block number.
	 * 
	 * As the request is async the DaemonHandler must take responsibility for post processing tasks
	 * once a response message is received.  <br><br>
	 *
	 * Required action is a call to: <br>
	 * BlockChainTracker.reportBlockNum(result, source);<br>
	 * <br>
	 * Where <i>result</i> is the block number reported.
	 * 
	 * @param blockTracker
	 * @param isNativeLongpollOriginated
	 */
	public abstract void doBlockCheck(BlockChainTracker blockTracker, boolean isNativeLongpollOriginated);

	/**
	 * This is primary interface to abstract the share submission process from the WorkSource.  The implementation
	 * should use whatever underlying transport to issue an asynchronous share submission.
	 * 
	 * As the request is async the DaemonHandler must take responsibility for a number of post processing tasks
	 * once a response message is received.  
	 * <br/><br/> * 
	 * 1/ on fail must call onShareSubmitFail()
	 * 2/ on response received must extract a true|false result and call onShareSubmitComplete
	 * 
	 * @param request
	 */
	public abstract void doShareSubmit(ShareSubmitRequest request);

	
	public abstract Object getSwapClientLock();
	
	public abstract long getWorkRequestClientIdleTimeout();
	
	public void onShareSubmitComplete(ShareEntry entry, boolean result) {
		entry.upstreamResult = result;
		shareSubmitter.getShareLogger().submitLogEntry(entry);
		Res.logDebug("work submit success, result: " + entry.upstreamResult);
		if (entry.upstreamResult) {
			source.getBlockTracker().fireBlockChange(entry.source, true);
			try {
				Res.logInfo(new Date() + "FOUND BLOCK - Worker: " + entry.worker.getUsername() + " Source: " + entry.source.getName() + " Solution: " + entry.solution);
			} catch (Throwable t) {}
		}
		if (entry.isRealSolution) {
			TempLogger.logRealShare(new Date() + " - finished sending real solution:           " + entry.solution + " - Upstream result:           " + entry.upstreamResult);
		} else if (entry.upstreamResult) {
			TempLogger.logRealShare(new Date() + " - upstream accepted solution but we didn't detect it! : " + entry.solution);
		}
	}

	public void onShareSubmitFail(ShareSubmitRequest request, String reason, Throwable t, boolean retry) {
		ShareEntry entry = request.getShareEntry();
		if (retry && entry.getUpstreamSubmitFails() < shareSubmitter.maxSubmitRetryOnConnectionFail) {
			entry.setUpstreamSubmitFails(entry.getUpstreamSubmitFails() + 1);
			shareSubmitter.submitUpstream(entry);
			return;
		}
		entry.setException(t);
		entry.reason = reason;
		entry.upstreamResult = false;
		shareSubmitter.getShareLogger().submitLogEntry(entry);
		
		if (entry.isRealSolution) {
			TempLogger.logRealShare(new Date() + " - failed sending real solution:           " + entry.solution + " reason: " + entry.reason);
			if (t != null) {
				TempLogger.logRealShare(t.getMessage());
			}
		}
		if (shareSubmitter.isShutdownRequested())
			releaseShareSubmitterShutdownLock(request);
	}

	public void releaseShareSubmitterShutdownLock(ShareSubmitRequest request) {
		synchronized (shareSubmitter.getFinalRequests()) {
			shareSubmitter.getFinalRequests().remove(this);
			if (shareSubmitter.getFinalRequests().size() == 0 && shareSubmitter.isFinalRequestSubmitted()) {
				shareSubmitter.getFinalRequests().notifyAll();
				synchronized (shareSubmitter.getShutdownLock()) {
					shareSubmitter.getShutdownLock().notifyAll();
				}
			}
		}
	}

	protected void processSingleResponse(String responseString, Long reportedBlock, int numRequested) {
		try {
			JsonRpcResponse response = new JsonRpcResponse(responseString, null);
			if (response.getResult() != null) {
				Work work = new Work(null, response.getResult());
				source.processSingleWork(work, reportedBlock, numRequested);
			} else if (response.getError() != null) {
				Stats.get().registerWorkReceivedError(source, response.getError().getMessage());
				Stats.get().registerUpstreamRequestFailed(source);
			}
	
		} catch (JSONException e) {
			Res.logError("InvalidJsonResponse", e);
			Stats.get().registerUpstreamRequestFailed(source);
		}
	}

	/**
	 * @return the source
	 */
	public WorkSource getSource() {
		return source;
	}

	/**
	 * @return the fetcherThread
	 */
	public WorkFetcherThread getFetcherThread() {
		return fetcherThread;
	}

}