package com.shadworld.poolserver.logging;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.HashMap;

import com.shadworld.jsonrpc.JsonRpcClient;
import com.shadworld.jsonrpc.JsonRpcRequest;
import com.shadworld.poolserver.BlockChainTracker;
import com.shadworld.poolserver.TempLogger;
import com.shadworld.poolserver.conf.Res;
import com.shadworld.poolserver.source.WorkSource;
import com.shadworld.poolserver.source.daemonhandler.JsonRpcDaemonHandler;

public class ShareSubmitterThread extends Thread {

	/**
	 * 
	 */
	private final ShareSubmitter workResultSubmitter;
	private final BlockChainTracker blockTracker;
	private boolean shutdown = false;
	// private boolean shutdownInCycle = true;
	private HashMap<WorkSource, Long> lastRequests = new HashMap();

	public ShareSubmitterThread(ShareSubmitter workResultSubmitter, final BlockChainTracker blockTracker, String name) {
		super(name);
		this.workResultSubmitter = workResultSubmitter;
		this.blockTracker = blockTracker;
	}

	public void run() {
		while (!shutdown) {
			int requestedWorks = 0;
			boolean exceptionThrown = false;
			try {

				mainloop: while ((this.workResultSubmitter.isShutdownRequested() && !shutdown)
						|| (!shutdown && !this.workResultSubmitter.isQueueEmptyUnsynchronized())) {
					// ensure we have one last cycle before shutdown to make
					// sure queue is cleared.
					if (this.workResultSubmitter.isShutdownRequested()
							&& this.workResultSubmitter.isQueueEmptyUnsynchronized()) {
						if (!shutdown) {// shutdown just called
							if (this.workResultSubmitter.isQueueEmptyUnsynchronized())
								// signal to release shutdown lock in this
								// handler
								// otherwise it will be released by the last
								// getworkClient request
								// to finish.
								this.workResultSubmitter.noRequestsToSubmitAfterShutdown = true;
						}
						shutdown = true;
					}
					// check and clear priority queue
					clearPriorityQueue();

					// check regular queue
					for (WorkSource source : this.workResultSubmitter.qs.keySet()) {
						ArrayDeque<ShareEntry> q = this.workResultSubmitter.getQueue(source);
						ShareEntry entry;
						synchronized (q) {
							entry = q.poll();
						}
						if (entry != null) {
							if (submitEntry(entry, false))
								continue mainloop;
						}
						requestedWorks++;
						// check between every submit that the priority queue
						// hasn't received new work.
						clearPriorityQueue();
					}

				}
				// TODO wait against qs for new submit.
				synchronized (this.workResultSubmitter.qs) {
					if (this.workResultSubmitter.isQueueEmptyUnsynchronized()
							&& !this.workResultSubmitter.isShutdownRequested()) {
						try {
							this.workResultSubmitter.qs.wait();
						} catch (InterruptedException e) {
						}
					}
				}
			} catch (Exception e) {
				exceptionThrown = true;
				Res.logError("Unhandled exception in " + Thread.currentThread().getName());
				e.printStackTrace();
				try {
					Thread.sleep(100);
				} catch (InterruptedException e1) {
				}
			}

		}
		// release shutdown waitlock only if there are no requests in
		// progress.
		if (this.workResultSubmitter.noRequestsToSubmitAfterShutdown) {
			synchronized (this.workResultSubmitter.getShutdownLock()) {
				this.workResultSubmitter.getShutdownLock().notifyAll();
			}
		}
	}

	private void clearPriorityQueue() {
		while (!this.workResultSubmitter.priorityQueue.isEmpty()) {
			synchronized (this.workResultSubmitter.priorityQueue) {
				ShareEntry entry = this.workResultSubmitter.priorityQueue.poll();
				submitEntry(entry, true);
			}
		}
	}

	/**
	 * 
	 * @param entry
	 * @param isPriority
	 * @return true if interrupted during sleep
	 */
	private boolean submitEntry(ShareEntry entry, boolean isPriority) {
		
		ShareSubmitRequest request = new ShareSubmitRequest(entry);
		
		if (!isPriority) {
			// if not min time between requests per source
			// elapsed
			// then sleep for a bit
			Long lastRequest = lastRequests.get(entry.source);
			if (lastRequest != null) {
				long now = System.currentTimeMillis();
				if (now < lastRequest + this.workResultSubmitter.minIntervalBetweenHttpRequests) {
					long diff = now - lastRequest;
					long sleep = this.workResultSubmitter.minIntervalBetweenHttpRequests - diff;
					if (sleep > 0) {
						try {
							Thread.sleep(this.workResultSubmitter.minIntervalBetweenHttpRequests - diff);
						} catch (InterruptedException e) {
							return true; // interrupt
											// usually
											// means
											// shutdown
											// requested
						}
					}
				}
			}
		}

		
		// send request
		if (this.workResultSubmitter.isShutdownRequested()) {
			synchronized (this.workResultSubmitter.getFinalRequests()) {
				this.workResultSubmitter.getFinalRequests().add(request);
				if (this.workResultSubmitter.isQueueEmpty())
					this.workResultSubmitter.setFinalRequestSubmitted(true);
			}
		}

		synchronized (this.workResultSubmitter.swapClientLock) {
//			ShareExchange ex;
//			ex = new ShareExchange(workResultSubmitter, blockTracker, request);
//			
//			JsonRpcClient getworkClient = this.workResultSubmitter.getClient(entry.source);
//			JsonRpcRequest getworkRequest = new JsonRpcRequest(JsonRpcRequest.METHOD_GETWORK, getworkClient.newRequestId(),
//					entry.solution);
//			// currentDownloads.add(ex);
//			if (entry.isRealSolution) {
//				TempLogger.logRealShare(new Date() + " - sending real solution upstream:         " + entry.solution);
//			}
//			try {
//				getworkClient.doRequest(getworkRequest, ex);
//			} catch (IOException e) {
//			}
			entry.source.getDaemonHandler().doShareSubmit(request);
			lastRequests.put(entry.source, System.currentTimeMillis());
		}
		return false;
	}

	public void shutdown() {
		this.workResultSubmitter.setShutdownRequested(true);
		synchronized (this.workResultSubmitter.qs) {
			this.workResultSubmitter.qs.notifyAll();
		}
		interrupt();
	}

}