package com.shadworld.poolserver.source;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.json.JSONException;

import com.shadworld.jsonrpc.JsonRpcRequest;
import com.shadworld.jsonrpc.JsonRpcResponse;
import com.shadworld.poolserver.conf.Conf;
import com.shadworld.poolserver.conf.Res;
import com.shadworld.poolserver.entity.Work;
import com.shadworld.poolserver.source.daemonhandler.WorkExchange;
import com.shadworld.poolserver.stats.Stats;

public class WorkFetcherThread extends Thread {

	/**
	 * 
	 */
	private final WorkSource workSource;
	final Set<GetWorkRequest> currentDownloads = Collections.synchronizedSet(new HashSet());
	final ArrayDeque<GetWorkRequest> queue = new ArrayDeque();

	public WorkFetcherThread(WorkSource workSource, String name) {
		super(name);
		this.workSource = workSource;
	}

	private boolean shutdown = false;

	@Override
	public void run() {
		while (!shutdown) {
			boolean exceptionThrown = false;
			try {
				// pop entries until a valid block is found
				boolean validFound = false;
				WorkEntry entry;
				synchronized (this.getWorkSource().cache) {
					while (!validFound && !this.getWorkSource().cache.isEmpty()) {
						entry = this.getWorkSource().cache.peek();
						if (this.getWorkSource().validateEntry(entry)) {
							validFound = true;
							// Stats.get().registerWorkExpired(this.workSource,
							// entry, false);
						} else {
							this.getWorkSource().cache.poll();
							Stats.get().registerWorkExpired(this.getWorkSource(), entry, true);
						}
					}
				}

				// pause if we are unsynced with the best blocknum
				if (getWorkSource().unSyncedBlockNum && getWorkSource().isBitcoinDaemon()) {
					long interval = getWorkSource().maxIntervalBetweenBlockCheck / 2;
					if (Res.isDebug()) {
						Res.logInfo("Pausing work fetcher for source: [" + getWorkSource().getName()
								+ "] due to blocknum out of sync");
					}
					while (getWorkSource().unSyncedBlockNum) {
						if (!getWorkSource().nativeLongpoll) {
							getWorkSource().getBlockTracker().fireBlockCheck(getWorkSource(), false);
						}
						synchronized (getWorkSource().cache) {
							getWorkSource().cache.wait(interval);
						}
					}
					if (Res.isDebug()) {
						Res.logInfo("Resuming work fetcher for source: [" + getWorkSource().getName()
								+ "] due to blocknum resync");
					}
				}

				// fetch new entries until cache reaches maximum size, notify
				// workAvailableLocks as new work comes in.
				int requestedWorks = 0;
				//ContentExchange ex;
				GetWorkRequest workRequest;

				while (!shutdown && this.getWorkSource().cache.size() < this.getWorkSource().currentCacheSize) {
					//ex = getWorkSource().buildContentExchangeForFetch();
					workRequest = workSource.handler.buildGetWorkRequest();
					// queue and pause if too many downloads in progress
					queue.add(workRequest);
					while (currentDownloads.size() >= this.getWorkSource().concurrentDownloadRequests) {
						try {
							synchronized (this) {
								wait(1000);
							}
						} catch (InterruptedException e) {
						}
						// in case we get stuck try to clear out timed out
						// connections.
						if ((currentDownloads.size() >= this.getWorkSource().concurrentDownloadRequests)) {
							long now = System.currentTimeMillis();
							long timeout = workSource.getDaemonHandler().getWorkRequestClientIdleTimeout();
							int removed = 0;
							synchronized (currentDownloads) {
//								for (Iterator<WorkExchange> i = currentDownloads.iterator(); i.hasNext();) {
//									WorkExchange x = i.next();
//									if (!x.isDoneNotify() && now > x.getStartTime() + timeout) {
//										x.cancel();
//										i.remove();
//										getWorkSource().state.registerConnectFail();
//										removed++;
//									}
//								}
								for (Iterator<GetWorkRequest> i = currentDownloads.iterator(); i.hasNext();) {
									GetWorkRequest x = i.next();
									if (!x.isDoneNotify() && now > x.getStartTime() + timeout) {
										x.cancel();
										i.remove();
										getWorkSource().state.registerConnectFail();
										removed++;
									}
								}
								if (removed > 0 && Res.isDebug()) {
									Res.logInfo("Cleared " + removed + " timed out fetcher connections");
								}
							}

						}

					}

					// check for throttle conditions (i.e. server busy or not
					// authorised responses)
					if (this.getWorkSource().delayUntilNextRequest > 0) {
						this.getWorkSource().delayInProgress = true;
						try {
							Thread.sleep(this.getWorkSource().delayUntilNextRequest);
						} catch (InterruptedException e) {
						}
						this.getWorkSource().lastDelayUntilNextRequest = this.getWorkSource().delayUntilNextRequest;
						this.getWorkSource().delayUntilNextRequest = 0;
						this.getWorkSource().delayInProgress = false;
					}
					Stats.get().registerUpstreamRequest(this.getWorkSource());
					synchronized (getWorkSource().getDaemonHandler().getSwapClientLock()) {
						//moved to handler
						//JsonRpcRequest getworkRequest = getWorkSource().buildGetworkRequest();
						currentDownloads.add(workRequest);
						//Stats.get().registerWorkHttpRequest(this.getWorkSource());
						//try {
						//	this.getWorkSource().client.doRequest(getworkRequest, ex);
						//} catch (IOException e) {
						//}
						workSource.getDaemonHandler().doGetWork(workRequest);
					}
					requestedWorks++;

					// if (cache.size() % 100 < 1 && cache.size() > 0) {
					// L.println(getDuplicateCalcString());
					// }

					try {
						Thread.sleep(this.getWorkSource().franticMode ? this.getWorkSource().minIntervalBetweenHttpRequestsWhenFrantic
								: this.getWorkSource().minIntervalBetweenHttpRequests);
					} catch (InterruptedException e) {

					}
				}
				this.getWorkSource().franticMode = false;
			} catch (Exception e) {
				exceptionThrown = true;
				Res.logError("Unhandled exception in " + Thread.currentThread().getName());
				e.printStackTrace();
			}

			synchronized (getWorkSource().cache) {
				if (getWorkSource().cache.size() >= this.getWorkSource().currentCacheSize || exceptionThrown) {
					try {
						getWorkSource().cache.wait(1000);
					} catch (InterruptedException e) {
					}
				}
			}
		}
	}

	public void notifyQueue(GetWorkRequest workRequest, boolean gotWork) {
		if (workRequest.isLongpoll()) {
			synchronized (this.getWorkSource().longpollThread) {
				this.getWorkSource().longpollThread.notifyAll();
			}
			return;
		}
		synchronized (this) {
			currentDownloads.remove(workRequest);
			GetWorkRequest waiter = queue.poll();
			if (waiter != null)
				synchronized (waiter) {
					waiter.notifyAll();
				}
			if (gotWork) {
				Object o = this.getWorkSource().workAvailableLock.poll();
				if (o != null)
					synchronized (o) {
						o.notifyAll();
					}
			}
			notifyAll();
		}
		workRequest.setDoneNotify(true);
	}

	// private ContentExchange getMultiWorkExchange() {
	//
	// return new MultiWorkExchange(this.workSource, this);
	// }

	// ContentExchange getSingleWorkExchange() {
	// return new SingleWorkExchange(this.workSource, this);
	// }

	//moved to JsonRpcDaemonHandler
//	public void processSingleResponse(String responseString, Long reportedBlock, int numRequested) {
//		try {
//			JsonRpcResponse response = new JsonRpcResponse(responseString, null);
//			if (response.getResult() != null) {
//				Work work = new Work(null, response.getResult());
//				processSingleWork(work, reportedBlock, numRequested);
//			} else if (response.getError() != null) {
//				Stats.get().registerWorkReceivedError(this.getWorkSource(), response.getError().getMessage());
//				Stats.get().registerUpstreamRequestFailed(getWorkSource());
//			}
//
//		} catch (JSONException e) {
//			Res.logError("InvalidJsonResponse", e);
//			Stats.get().registerUpstreamRequestFailed(getWorkSource());
//		}
//	}
	
	//moved to WorkSource
//	public void processSingleWork(Work work, Long reportedBlock, int numRequested) {
//		
//		if (getWorkSource().isBitcoinDaemon() && (!getWorkSource().nativeLongpoll || getWorkSource().isNativeLongpollVerificationEnabled()) ) {
//			
//			//handling for using prev block hash as a block change indicator.
//			if (getWorkSource().acceptNextPrevBlockHash) {
//				synchronized (getWorkSource().cache) {
//					// recheck inside sync block
//					if (getWorkSource().acceptNextPrevBlockHash) {
//						String prev = work.getData();
//						if (prev != null && prev.length() > 72) {
//							prev = prev.substring(8, 72);
//							getWorkSource().prevBlockHash = prev;
//							getWorkSource().acceptNextPrevBlockHash = false;
//							if (Res.isTrace(Res.TRACE_BLOCKMON)) {
//								Res.logInfo("Repopulating prev_block_hash for source: [" + getWorkSource().getName() + "] - " + prev);
//							}
//						}
//					}
//				}
//			} else if (getWorkSource().prevBlockHash != null && !isPrevBlockHashMatch(work)
//					&& System.currentTimeMillis() > getWorkSource().lastBlockCheckTime + 10) {
//				getWorkSource().lastBlockCheckTime = System.currentTimeMillis();
//				if (Res.isTrace(Res.TRACE_BLOCKMON)) {
//					Res.logInfo("Detected possible block change using prev_block_hash on source: [" + getWorkSource().getName() + "]");
//				}
//				getWorkSource().getBlockTracker().fireBlockCheck(getWorkSource(), false);
//				String prev = work.getData();
//				if (prev != null && prev.length() > 72) {
//					prev = prev.substring(8, 72);
//					getWorkSource().prevBlockHash = prev;
//					getWorkSource().acceptNextPrevBlockHash = false;
//					if (Res.isTrace(Res.TRACE_BLOCKMON)) {
//						Res.logInfo("Repopulating prev_block_hash for source: [" + getWorkSource().getName() + "] - " + prev);
//					}
//				}
//			}
//		}
//
//		WorkEntry entry = new WorkEntry(this.getWorkSource(), work, System.currentTimeMillis(), reportedBlock);
//		this.getWorkSource().cacheEntry(entry);
//		getWorkSource().state.registerIncomingWork(1, numRequested);
//		Stats.get().registerUpstreamRequestSuccess(getWorkSource());
//	}

	//moved to WorkSource
//	/**
//	 * checks first 5 chars for a match.
//	 * 
//	 * @param work
//	 * @return
//	 */
//	private boolean isPrevBlockHashMatch(Work work) {
//		String prev = work.getData();
//		if (prev != null && prev.length() > 72) {
//			for (int i = 0; i < 5; i++) {
//				if (prev.charAt(i + 8) != getWorkSource().prevBlockHash.charAt(i))
//					return false;
//			}
//			return true;
//		}
//		return false;
//	}

	public void shutdown() {
		shutdown = true;
		synchronized (this) {
			notifyAll();
			interrupt();
		}
		
		//this is called later in WorkSource.shutdown();
//		try {
//			this.getWorkSource().getDaemonHandler().shutdown();
//		} catch (Exception e) {
//		}
	}

	public WorkSource getWorkSource() {
		return workSource;
	}
	
}