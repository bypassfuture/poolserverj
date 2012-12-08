package com.shadworld.poolserver.logging;

import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

import com.shadworld.jsonrpc.JsonRpcClient;
import com.shadworld.poolserver.BlockChainTracker;
import com.shadworld.poolserver.conf.Res;
import com.shadworld.poolserver.entity.Worker;
import com.shadworld.poolserver.source.WorkSource;
import com.shadworld.utils.L;

public class ShareSubmitter {

	final Set currentDownloads = Collections.synchronizedSet(new HashSet());
	// submit queue per source
	final HashMap<WorkSource, ArrayDeque<ShareEntry>> qs = new HashMap();
	// priority queue
	final ArrayDeque<ShareEntry> priorityQueue = new ArrayDeque();

	// queued content exchanges // probably redundant we use max connection per
	// getworkClient in the HttpClient now.
	final ArrayDeque<ContentExchange> queue = new ArrayDeque();

	private final Object shutdownLock = new Object();

	private boolean shutdownRequested = false;
	boolean noRequestsToSubmitAfterShutdown = false;
	private final HashSet<ShareSubmitRequest> finalRequests = new HashSet(); // used
																		// during
																		// shutdown
																		// to
																		// check
																		// if
																		// lastRequest
	private boolean finalRequestSubmitted = false;

	final ShareLogger shareLogger = new ShareLogger();
	final private ShareSubmitterThread shareSubmitterThread;
	
	private final BlockChainTracker blockTracker;

	// private ArrayDeque<ShareEntry> q = new ArrayDeque();

	private boolean shutdown = false;

	// public int concurrentDownloadRequests;
	public long minIntervalBetweenHttpRequests;
	final public Object swapClientLock = new Object();
	public int maxSubmitRetryOnConnectionFail;

	public ShareSubmitter(final BlockChainTracker blockTracker) {
		super();
		this.blockTracker = blockTracker;
		shareSubmitterThread = new ShareSubmitterThread(this, blockTracker, "upstream-work-submit-handler");

	}

	public void start() {
		getShareSubmitterThread().start();
		shareLogger.start();
	}

	ArrayDeque<ShareEntry> getQueue(WorkSource source) {
		ArrayDeque<ShareEntry> q;
		q = qs.get(source);
		if (q == null) {
			q = new ArrayDeque<ShareEntry>();
			synchronized (qs) {
				qs.put(source, q);
			}
		}
		return q;
	}

	boolean isQueueEmptyUnsynchronized() {
		if (!priorityQueue.isEmpty())
			return false;
		for (ArrayDeque<ShareEntry> q : qs.values()) {
			if (!q.isEmpty())
				return false;
		}
		return true;
	}

	public boolean isQueueEmpty() {
		synchronized (qs) {
			synchronized (priorityQueue) {
				if (!priorityQueue.isEmpty())
					return false;
			}
			for (ArrayDeque<ShareEntry> q : qs.values()) {
				synchronized (q) {
					if (!q.isEmpty())
						return false;
				}
			}
		}
		return true;
	}

	/**
	 * submits work upstream where the work is valid for the real difficulty.
	 * This queue has highest priority.
	 * 
	 * @param entry
	 */
	public void submitUpstream(ShareEntry entry) {
		if (entry.ourResult && entry.reportUpstream) {
			ArrayDeque<ShareEntry> q = getQueue(entry.source);
			synchronized (qs) {
				synchronized (q) {
					q.offerLast(entry);
				}
				qs.notifyAll();
			}
		} else {
			shareLogger.submitLogEntry(entry);
		}
	}

	/**
	 * submits work upstream where the work is valid for the real difficulty.
	 * This queue has highest priority.
	 * 
	 * @param entry
	 */
	public void submitPriorityUpstream(ShareEntry entry) {
		if (entry.ourResult) {
			synchronized (qs) {
				synchronized (priorityQueue) {
					priorityQueue.offerLast(entry);
				}
				qs.notifyAll();
			}
		}
	}

//	/**
//	 * this will force the current JsonRpcClient (and it's jetty http getworkClient) to
//	 * be discarded and replaced with a new one.
//	 * 
//	 * @param concurrentDownloadRequests
//	 */
//	public void refreshClient(WorkSource source) {
//		// this.maxConcurrentDownloadRequests = concurrentDownloadRequests;
//		synchronized (swapClientLock) {
//			JsonRpcClient oldClient = clients.remove(source);
//			if (oldClient != null) {
//				try {
//					oldClient.getClient().stop();
//				} catch (Exception e) {
//				}
//			}
//		}
//	}
//
//	private JsonRpcClient initClient(WorkSource source) {
//		JsonRpcClient client;
//		synchronized (swapClientLock) {
//			client = new JsonRpcClient(source.getUrl(), source.getUsername(), source.getPassword());
//			QueuedThreadPool pool = new QueuedThreadPool();
//			pool.setName("HttpClient-" + getShareSubmitterThread().getName());
//			pool.setDaemon(true);
//			client.getClient().setMaxConnectionsPerAddress(source.getMaxConcurrentUpstreamSubmits());
//			client.getClient().setThreadPool(pool);
//		}
//		return client;
//	}

	public void shutdown(PrintWriter writer) {
		shutdown = true;
		Res.logInfo(writer, "Flushing cached shares...");
		shareLogger.flushImmediate();// most important to get this done
											// first so
											// we don't lose any cached data
		Res.logInfo(writer, "Flush shares cache complete...");
		synchronized (getShutdownLock()) {
			try {
				Res.logInfo(writer, "Waiting to complete upstream share submits...");
				getShareSubmitterThread().shutdown();
				getShutdownLock().wait();
				Res.logInfo(writer, "Finished upstream submits...");
			} catch (InterruptedException e) {
			}
		}

		Res.logInfo(writer, "Flushing final shares...");
		shareLogger.shutdown(false);
		Res.logInfo(writer, "All share submits flushed...");
		synchronized (this) {
			notifyAll();
		}
		for (ArrayDeque<ShareEntry> q : qs.values()) {
			synchronized (q) {
				q.notifyAll();
			}
		}

	}

	// /**
	// * @return the concurrentDownloadRequests
	// */
	// public int getMaxConcurrentDownloadRequests() {
	// return concurrentDownloadRequests;
	// }
	//
	// /**
	// * @param concurrentDownloadRequests the concurrentDownloadRequests
	// to set
	// */
	// public void setMaxConcurrentDownloadRequests(int
	// concurrentDownloadRequests) {
	// this.maxConcurrentDownloadRequests = concurrentDownloadRequests;
	// }

	/**
	 * @return the minIntervalBetweenHttpRequests
	 */
	public long getMinIntervalBetweenHttpRequests() {
		return minIntervalBetweenHttpRequests;
	}

	/**
	 * @param minIntervalBetweenHttpRequests
	 *            the minIntervalBetweenHttpRequests to set
	 */
	public void setMinIntervalBetweenHttpRequests(long minIntervalBetweenHttpRequests) {
		this.minIntervalBetweenHttpRequests = minIntervalBetweenHttpRequests;
	}

	/**
	 * @return the maxSubmitRetryOnConnectionFail
	 */
	public int getMaxSubmitRetryOnConnectionFail() {
		return maxSubmitRetryOnConnectionFail;
	}

	/**
	 * @param maxSubmitRetryOnConnectionFail
	 *            the maxSubmitRetryOnConnectionFail to set
	 */
	public void setMaxSubmitRetryOnConnectionFail(int maxSubmitRetryOnConnectionFail) {
		this.maxSubmitRetryOnConnectionFail = maxSubmitRetryOnConnectionFail;
	}

	/**
	 * @return the shareLogger
	 */
	public ShareLogger getShareLogger() {
		return shareLogger;
	}

	public void join() {
		try {
			getShareSubmitterThread().join();
		} catch (InterruptedException e) {
		}
	}

	public boolean isShutdownRequested() {
		return shutdownRequested;
	}

	public HashSet<ShareSubmitRequest> getFinalRequests() {
		return finalRequests;
	}

	public Object getShutdownLock() {
		return shutdownLock;
	}

	public boolean isFinalRequestSubmitted() {
		return finalRequestSubmitted;
	}

	public void setFinalRequestSubmitted(boolean finalRequestSubmitted) {
		this.finalRequestSubmitted = finalRequestSubmitted;
	}

	public void setShutdownRequested(boolean shutdownRequested) {
		this.shutdownRequested = shutdownRequested;
	}

	public ShareSubmitterThread getShareSubmitterThread() {
		return shareSubmitterThread;
	}

}
