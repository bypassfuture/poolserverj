package com.shadworld.poolserver.source.daemonhandler;

import java.io.IOException;
import java.util.Date;

import org.eclipse.jetty.util.thread.QueuedThreadPool;

import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import com.shadworld.jsonrpc.JsonRpcClient;
import com.shadworld.jsonrpc.JsonRpcRequest;
import com.shadworld.poolserver.BlockChainTracker;
import com.shadworld.poolserver.PSJExchange;
import com.shadworld.poolserver.PoolServer;
import com.shadworld.poolserver.TempLogger;
import com.shadworld.poolserver.conf.Res;
import com.shadworld.poolserver.logging.ShareEntry;
import com.shadworld.poolserver.logging.ShareExchange;
import com.shadworld.poolserver.logging.ShareSubmitRequest;
import com.shadworld.poolserver.source.GetWorkRequest;
import com.shadworld.poolserver.source.WorkSource;
import com.shadworld.poolserver.stats.Stats;

public class JsonRpcDaemonHandler extends DaemonHandler {

	JsonRpcClient getworkClient;
	JsonRpcClient shareSubmitClient;
	

	private final Object swapGetworkClientLock = new Object();
	private final Object swapShareSubmitClientLock = new Object();
	
	
	
	public JsonRpcDaemonHandler(WorkSource workSource) {
		super(workSource);
	}

	/**
	 * @return the getworkClient
	 */
	public JsonRpcClient getClient() {
		return getworkClient;
	}
	
	/* (non-Javadoc)
	 * @see com.shadworld.poolserver.source.daemonhandler.DaemonHandler#doShareSubmit(com.shadworld.poolserver.logging.ShareSubmitRequest)
	 */
	@Override
	public void doShareSubmit(ShareSubmitRequest request) {
		ShareEntry entry = request.getShareEntry();
		ShareExchange ex;
		ex = new ShareExchange(shareSubmitter, source.getBlockTracker(), request);
		
		JsonRpcRequest getworkRequest = new JsonRpcRequest(JsonRpcRequest.METHOD_GETWORK, shareSubmitClient.newRequestId(),
				entry.solution);
		// currentDownloads.add(ex);
		if (entry.isRealSolution) {
			TempLogger.logRealShare(new Date() + " - sending real solution upstream:         " + entry.solution);
		}
		try {
			shareSubmitClient.doRequest(getworkRequest, ex);
		} catch (IOException e) {
		}
	}
	
	private void initShareSubmitClient() {
		synchronized (swapShareSubmitClientLock) {
			shareSubmitClient = new JsonRpcClient(source.getUrl(), source.getUsername(), source.getPassword());
			QueuedThreadPool pool = new QueuedThreadPool();
			pool.setName("HttpClient-" + shareSubmitter.getShareSubmitterThread().getName());
			pool.setDaemon(true);
			shareSubmitClient.getClient().setMaxConnectionsPerAddress(source.getMaxConcurrentUpstreamSubmits());
			shareSubmitClient.getClient().setThreadPool(pool);
		}
	}
	
	/* (non-Javadoc)
	 * @see com.shadworld.poolserver.source.daemonhandler.DaemonHandler#doBlockCheck(com.shadworld.poolserver.BlockChainTracker, boolean)
	 */
	@Override
	public void doBlockCheck(BlockChainTracker blockTracker, boolean isNativeLongpollOriginated) {
		JsonRpcRequest request = new JsonRpcRequest(JsonRpcRequest.METHOD_GETBLOCKNUMBER, getworkClient.newRequestId());
		PSJExchange ex = new BlockCheckExchange(blockTracker, source, isNativeLongpollOriginated);
		
		try {
			Res.getSharedClient().doRequest(source.getUrl(), request, ex);
		} catch (IOException e) {
		}
	}
	
	/* (non-Javadoc)
	 * @see com.shadworld.poolserver.source.daemonhandler.DaemonHandler#doGetWork(com.shadworld.poolserver.source.GetWorkRequest)
	 */
	@Override
	public void doGetWork(GetWorkRequest workRequest) {
		JsonRpcRequest request = new JsonRpcRequest(JsonRpcRequest.METHOD_GETWORK, getworkClient.newRequestId());
		getworkClient.doRequest(request);
		workRequest.setStartTime(System.currentTimeMillis());
		Stats.get().registerWorkHttpRequest(source);
		try {
			WorkExchange ex = buildContentExchangeForFetch(workRequest);
			((JsonGetWorkRequest) workRequest).setExchange(ex);
			getworkClient.doRequest(request.toJSONString(0), ex);
		} catch (IOException e) {
		}
	}
	

	public JsonGetWorkRequest buildGetWorkRequest() {
		return new JsonGetWorkRequest(source);
	}
	
	protected WorkExchange buildContentExchangeForFetch(GetWorkRequest workRequest) {
		return new SingleWorkExchange(source, this, workRequest);
	}
	
	JsonRpcClient initGetworkClient() {
		synchronized (getSwapClientLock()) {
			getworkClient = new JsonRpcClient(source.isSupportUpstreamLongPoll(), source.getUrl(), source.getUsername(), source.getPassword());
			QueuedThreadPool pool = new QueuedThreadPool();
			pool.setName("HttpClient-" + source.getWorkFetcherThread().getName());
			pool.setDaemon(true);
			getworkClient.getClient().setThreadPool(pool);
			getworkClient.getClient().setMaxConnectionsPerAddress(source.getConcurrentDownloadRequests());

		}
		return getworkClient;
	}
	
	@Override
	public void start() {
		initGetworkClient();
		initShareSubmitClient();
	}
	
	@Override
	public void shutdown() {
		try {
			getworkClient.getClient().stop();
		} catch (Exception e) {
		}
		try {
			shareSubmitClient.getClient().stop();
		} catch (Exception e) {
		}

	}
	
	public void swapGetworkClient() {
		synchronized (getSwapClientLock()) {
			JsonRpcClient oldClient = getworkClient;
			initGetworkClient();
			try {
				oldClient.getClient().stop();
			} catch (Exception e) {
			}
		}
		
	}
	/**
	 * this will force the current JsonRpcClient (and it's jetty http getworkClient) to
	 * be discarded and replaced with a new one.
	 * 
	 * @param concurrentDownloadRequests
	 */
	public void swapShareSubmitClient() {
		// this.maxConcurrentDownloadRequests = concurrentDownloadRequests;
		synchronized (swapShareSubmitClientLock) {
			JsonRpcClient oldClient = shareSubmitClient;
			if (oldClient != null) {
				try {
					oldClient.getClient().stop();
				} catch (Exception e) {
				}
			}
			initShareSubmitClient();
		}
	}

	
	public Object getSwapClientLock() {
		return swapGetworkClientLock;
	}
	
	/* (non-Javadoc)
	 * @see com.shadworld.poolserver.source.daemonhandler.DaemonHandler#getWorkRequestClientIdleTimeout()
	 */
	@Override
	public long getWorkRequestClientIdleTimeout() {
		return getworkClient.getClient().getIdleTimeout();
	}



	private class JsonGetWorkRequest extends GetWorkRequest {

		private WorkExchange exchange;
		
		public JsonGetWorkRequest(WorkSource source) {
			super(source);
			
		}

		@Override
		public void cancel() {
			exchange.cancel();
		}

		/**
		 * @param exchange the exchange to set
		 */
		public void setExchange(WorkExchange exchange) {
			this.exchange = exchange;
		}
		
		
		
	}

}
