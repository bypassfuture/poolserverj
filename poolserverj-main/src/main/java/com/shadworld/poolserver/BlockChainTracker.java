package com.shadworld.poolserver;

import java.io.IOException;
import java.text.NumberFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.io.RuntimeIOException;
import org.json.JSONObject;

import com.google.bitcoin.core.PSJBlock;
import com.google.bitcoin.core.NetworkParameters;
import com.google.bitcoin.core.ProtocolException;
import com.google.bitcoin.core.VerificationException;
import com.shadworld.jsonrpc.JsonRpcClient;
import com.shadworld.jsonrpc.JsonRpcRequest;
import com.shadworld.jsonrpc.JsonRpcResponse;
import com.shadworld.jsonrpc.exception.JsonRpcResponseException;
import com.shadworld.poolserver.conf.Conf;
import com.shadworld.poolserver.conf.Res;
import com.shadworld.poolserver.notify.NotifyBlockChangeMethod;
import com.shadworld.poolserver.servlet.LongpollContinuation;
import com.shadworld.poolserver.source.DaemonSource;
import com.shadworld.poolserver.source.WorkEntry;
import com.shadworld.poolserver.source.WorkSource;
import com.shadworld.utils.L;

public class BlockChainTracker extends Thread {

	private static final int MAX_INTERVAL_BETWEEN_BLOCK_CHECKS = 2000;

	// final private ArrayDeque<LongpollContinuation> longpollWaitLocks = new
	// ArrayDeque();
	// final private Set<Continuation> longpollUnregisteredWaitLocks = new
	// HashSet();

	final PoolServer server;
	WorkProxy proxy;
	WorkerProxy workerProxy;
	LongpollHandler longpollHandler;

	ArrayList<DaemonSource> localSources;
	final HashSet<DaemonSource> localSourcesSet = new HashSet();
	ArrayList<WorkSource> upstreamSources;
	final HashSet<WorkSource> upstreamSourcesSet = new HashSet();
	final ArrayList<WorkSource> allSources = new ArrayList();
	final HashSet<WorkSource> allSourcesSet = new HashSet();
	int totalSources;

	long lastCheckTime = System.currentTimeMillis();
	long minTimeBetweenChecksForLocalSource = 1000;
	long minTimeBetweenChecksForUpstreamSource = 5000;

	private boolean shutdown = false;

	private int difficultyReportsToCheck = 20;
	private boolean checkDifficultyTargets = true;
	final private Object difficultyCheckWaitLock = new Object();

	private volatile long currentBlock;
	private volatile long lastBlock;
	private boolean firstBlockChange = true;

	private long nextBlockChangeBlockNum = -1;

	private NotifyBlockChangeMethod notifyBlockChangeMethod;
	private long notifyBlockChangedelay = 5000;

	private boolean allSourcesOnCurrentBlock = true;
	final private HashSet<WorkSource> sourcesOnCurrentBlock = new HashSet();
	// private WorkSource[][] sourcesOnCurrentBlockArray;
	private boolean acceptNotifyBlockChange = true;

	public BlockChainTracker(PoolServer poolServer) {
		super("block-chain-tracker");
		this.server = poolServer;
	}

	public static void main(String[] args) {
		L.println(calcNextRetarget(134512));
	}

	public static long calcNextRetarget(long currentBlockNum) {
		long lastRetarget = ((currentBlockNum) / 2016) * 2016;
		return lastRetarget + 2015;
	}

	/**
	 * don't call this until proxy is fully configured.
	 * 
	 * @param proxy
	 */
	public void setWorkProxy(WorkProxy proxy) {
		this.proxy = proxy;
		localSources = proxy.getDaemonSources();
		localSourcesSet.addAll(localSources);
		upstreamSources = proxy.getNonDaemonSources();
		upstreamSourcesSet.addAll(upstreamSources);
		allSources.addAll(localSources);
		allSources.addAll(upstreamSources);
		totalSources = allSources.size();
		allSourcesSet.addAll(allSources);

		// sourcesOnCurrentBlockArray = new WorkSource[allSources.size()][];
		// for (int i = 0; i < allSources.size(); i++) {
		// sourcesOnCurrentBlockArray[i] = new WorkSource[i + 1];
		// }
		// sourcesOnCurrentBlockArray[sourcesOnCurrentBlockArray.length - 1] =
		// allSources
		// .toArray(new WorkSource[allSources.size()]);
	}

	public void setWorkerProxy(WorkerProxy workerProxy) {
		this.workerProxy = workerProxy;
	}

	public long getCurrentBlock() {
		return currentBlock;
	}

	public void shutdown() {
		shutdown = true;
		longpollHandler.shutdown();
		if (notifyBlockChangeMethod != null) {
			notifyBlockChangeMethod.shutdown();
		}
		synchronized (this) {
			notifyAll();
		}
		interrupt();
	}

	// public void registerLongpollWaitLock(LongpollContinuation waitLock) {
	// synchronized (longpollWaitLocks) {
	// longpollWaitLocks.addFirst(waitLock);
	// }
	// }

	// public void unregisterLongpollWaitLock(Continuation cont) {
	// synchronized (longpollUnregisteredWaitLocks) {
	// if (!longpollUnregisteredWaitLocks.add(cont) && Res.isDebug()) {
	// Res.logInfo("Unregistered LP Continuation that was already unregistered: "
	// + cont.toString().replace("org.eclipse.jetty.server.", ""));
	// }
	// }
	// if (Res.isDebug()) {
	// int instances = 0;
	// for (LongpollContinuation waitLock : longpollWaitLocks) {
	// if (waitLock.continuation == cont) {
	// instances++;
	// Res.logInfo("Unregistered LP for worker: " +
	// waitLock.worker.getUsername());
	// }
	// }
	// if (instances == 0) {
	// Res.logInfo("Could not find Unregistered Continuation: "
	// + cont.toString().replace("org.eclipse.jetty.server.", ""));
	// Thread.currentThread().dumpStack();
	// } else if (instances > 1) {
	// Res.logInfo("Found multiple waitLocks for unregistered Continuation: "
	// + cont.toString().replace("org.eclipse.jetty.server.", ""));
	// }
	// }
	// }

	// public int getNumLongpollConnections() {
	// return longpollWaitLocks.size() - longpollUnregisteredWaitLocks.size();
	// }

	public int getNumLongpollConnections() {
		return longpollHandler.getNumLongpollConnections();
	}

	private void notifyLongpollClients() {
		// ArrayList<LongpollContinuation> waitLocks = new ArrayList();
		List<LongpollContinuation> waitLocks = longpollHandler.flushQueue();
		// synchronized (longpollWaitLocks) {
		// synchronized (longpollUnregisteredWaitLocks) {
		// while (!longpollWaitLocks.isEmpty()) {
		// LongpollContinuation cont = longpollWaitLocks.pollLast();
		// if (!longpollUnregisteredWaitLocks.contains(cont.continuation)) {
		// waitLocks.add(cont);
		// if (Res.isDebug()) {
		// Res.logInfo("Adding LP waitLock to queue from: " +
		// cont.worker.getUsername() + " - "
		// + cont.continuation.toString().replace("org.eclipse.jetty.server.",
		// ""));
		// }
		// } else if (Res.isDebug()) {
		// Res.logInfo("Rejecting unregistered LP waitLock: " +
		// cont.worker.getUsername() + " - "
		// + cont.continuation.toString().replace("org.eclipse.jetty.server.",
		// ""));
		// }
		// }
		// longpollUnregisteredWaitLocks.clear();
		// }
		// }
		NotifyLongpollClientsTask notifyLongpollClientsTask = new NotifyLongpollClientsTask("notify-lp-clients",
				waitLocks);
		longpollNotifyClientsExecutor.submit(notifyLongpollClientsTask);
	}

	public void fireBlockChange(WorkSource workSource) {
		fireBlockChange(workSource, false);
	}

	public void fireBlockChange(WorkSource workSource, boolean wonBlock) {
		if (acceptNotifyBlockChange) {
			try {
				synchronized (sourcesOnCurrentBlock) {
					if (!acceptNotifyBlockChange)
						return;
					acceptNotifyBlockChange = false;
					sourcesOnCurrentBlock.clear();
					sourcesOnCurrentBlock.add(workSource);	
					if (totalSources == sourcesOnCurrentBlock.size())
					allSourcesOnCurrentBlock = true;
					if (allSourcesOnCurrentBlock && Res.isDebug()) {
						Res.logInfo("Single source synced to new block.  Total sync time: "
								+ (System.currentTimeMillis() - blockChangeStart) + "ms");
					}
				}
				proxy.notifyBlockChange();
				for (WorkSource source : localSources)
					source.notifyBlockChange(currentBlock);
				for (WorkSource source : upstreamSources)
					source.notifyBlockChange(currentBlock);
				if (workSource != null)
					workSource.resyncToBlockTracker();
				acceptNotifyBlockChange = false;
				// tell the database writer to back off for a while since we'll
				// be
				// busy servicing longpolls.
				proxy.getWorkResultSubmitter().getShareLogger().notifyBlockChange();

				if (notifyBlockChangeMethod != null) {
					notifyBlockChangeMethod.notifyBlockChange(notifyBlockChangedelay, wonBlock, workSource.getName());
				}
				long nextRetarget = calcNextRetarget(lastBlock == 0 ? currentBlock : lastBlock);
				if (nextRetarget > currentBlock - 3 && nextRetarget < currentBlock + 2) {
					difficultyReportsToCheck = totalSources * 5;
					checkDifficultyTargets = true;
				}
				workerProxy.notifyBlockChange();
				synchronized (this) {
					notifyAll();
				}
				notifyLongpollClients();
			} finally {
				acceptNotifyBlockChange = true;
			}
		}

	}

	public void reportDifficultyTarget(WorkEntry entry) {
		if (checkDifficultyTargets) {
			synchronized (difficultyCheckWaitLock) {
				if (checkDifficultyTargets) {
					try {
						String blockData = entry.getWork().getData();
						PSJBlock block = new PSJBlock(Res.networkParameters(), blockData);
						if (!block.getDifficultyTargetAsInteger().equals(Res.getRealDifficultyTargetAsInteger())) {
							Res.setRealDifficultyTarget(block.getDifficultyTarget());
							checkDifficultyTargets = false;
						} else {
							if (--difficultyReportsToCheck <= 0)
								checkDifficultyTargets = false;
						}
					} catch (Exception e) {
						Res.logException(e);
					}
				}
			}
		}
	}

	public boolean isValid(WorkSource source) {
		if (allSourcesOnCurrentBlock)
			return true;
		boolean valid;
		synchronized (sourcesOnCurrentBlock) {
			valid = sourcesOnCurrentBlock.contains(source);
		}
		return valid;
	}

	public void reportBlockNum(Long blockNum, WorkSource workSource) {
		reportBlockNum(blockNum, workSource, false);
	}

	public void reportNativeLonpoll(WorkSource workSource) {
		if (workSource.isNativeLongpollVerificationEnabled()) {
			fireBlockCheck(workSource, true);
		} else {
			reportBlockNum(null, workSource, true);
		}
	}

	private long blockChangeStart;

	private void reportBlockNum(Long blockNum, WorkSource workSource, boolean isNative) {
		if ((blockNum == null || blockNum == -1) && !isNative)
			return;
		workSource.setLastBlockCheckTime(System.currentTimeMillis());
		if (isNative)
			blockNum = workSource.nativeLongpollIncrementBlockNum();
		else
			workSource.setMyCurrentBlock(blockNum);
		if (blockNum > currentBlock) {
			blockChangeStart = System.currentTimeMillis();
			if (Res.isDebug()) {
				if (currentBlock == 0)
					Res.logInfo(workSource + " found initial block number: " + blockNum);
				else
					Res.logInfo("New Block detected [" + blockNum + (isNative ? "] via native longpoll" : "")
							+ "] from source: " + workSource + ".  " + (allSources.size() - 1) + " lagging sources.");
			}
			lastBlock = currentBlock;
			currentBlock = blockNum;
			if (lastBlock > 0) // first time it's updated the last block will be
								// 0 or -1 so it's not really a block change.
				fireBlockChange(workSource);
			else {
				// we have to wait to do this until we have a valid block number
				proxy.restoreWorkMap(Conf.get().getWorkMapFile());

				// this shouldn't be here but the developer was too lazy to make
				// a
				// delayed task. We just want to delay this a bit so these
				// threads
				// don't appear at the top of the list during debugging.
				longpollNotifyClientsExecutor.prestartAllCoreThreads();
				longpollDispatchExecutor.prestartAllCoreThreads();

			}
		} else if (!allSourcesOnCurrentBlock && blockNum == currentBlock) {

			synchronized (sourcesOnCurrentBlock) {
				if (Res.isDebug() && !sourcesOnCurrentBlock.contains(workSource)) {
					Res.logInfo("Source: " + workSource + " caught up to current block. "
							+ (allSources.size() - sourcesOnCurrentBlock.size() - 1) + " lagging sources.");
				}
				sourcesOnCurrentBlock.add(workSource);
				if (sourcesOnCurrentBlock.size() == totalSources) {
					allSourcesOnCurrentBlock = true;
					if (Res.isDebug()) {
						Res.logInfo("All sources on current block.  Total sync time: "
								+ (System.currentTimeMillis() - blockChangeStart) + "ms");
					}
				}
				workSource.resyncToBlockTracker();
			}

		}
	}

	public WorkSource[] getSourcesOnCurrentBlock() {
		synchronized (sourcesOnCurrentBlock) {
			return sourcesOnCurrentBlock.toArray(new WorkSource[sourcesOnCurrentBlock.size()]);
		}
	}

	public boolean isSourceOnCurrentBlock(WorkSource source) {
		return source.getMyCurrentBlock() == currentBlock;
	}

	/**
	 * @return the allSourcesOnCurrentBlock
	 */
	public boolean isAllSourcesOnCurrentBlock() {
		return allSourcesOnCurrentBlock;
	}

	public void reportLongpollResponse(Long reportedBlock, WorkSource workSource) {
		reportBlockNum(reportedBlock, workSource);
	}

	/**
	 * @return the notifyBlockChangeMethod
	 */
	public NotifyBlockChangeMethod getNotifyBlockChangeMethod() {
		return notifyBlockChangeMethod;
	}

	/**
	 * @param notifyBlockChangeMethod
	 *            the notifyBlockChangeMethod to set
	 */
	public void setNotifyBlockChangeMethod(NotifyBlockChangeMethod notifyBlockChangeMethod) {
		if (this.notifyBlockChangeMethod != null) {
			this.notifyBlockChangeMethod.shutdown();
		}
		this.notifyBlockChangeMethod = notifyBlockChangeMethod;
	}

	/**
	 * @return the notifyBlockChangedelay
	 */
	public long getNotifyBlockChangedelay() {
		return notifyBlockChangedelay;
	}

	/**
	 * @return the minTimeBetweenChecksForLocalSource
	 */
	public long getMinTimeBetweenChecksForLocalSource() {
		return minTimeBetweenChecksForLocalSource;
	}

	/**
	 * @param notifyBlockChangedelay
	 *            the notifyBlockChangedelay to set
	 */
	public void setNotifyBlockChangedelay(long notifyBlockChangedelay) {
		this.notifyBlockChangedelay = notifyBlockChangedelay;
	}

	public void fireBlockCheck(final WorkSource source, boolean isNativeLongpollOriginated) {
		fireBlockCheck(source, System.currentTimeMillis(), Res.getSharedClient(), isNativeLongpollOriginated);
	}

	public void fireBlockCheck(final WorkSource source, final long timeNow, JsonRpcClient client,
			boolean isNativeLongpollOriginated) {
		// if (source.isNativeLongpoll() &&
		// !source.isNativeLongpollVerificationEnabled()) {
		// Res.logError("Cancelling block check due to native longpolling verification disabled.  We shouldn't have got here!");
		// }
	
		if (isNativeLongpollOriginated && Res.isTrace()) {
			Res.logTrace(Res.TRACE_BLOCKMON, "Firing verification for native longpoll from source: " + source
					+ ". Source block num:" + source.getMyCurrentBlock());
		}
		source.setLastBlockCheckTime(timeNow);
		source.getDaemonHandler().doBlockCheck(this ,isNativeLongpollOriginated);
	}

	@Override
	public void run() {
		while (!shutdown) {
			JsonRpcClient client = Res.getSharedClient();
			long sleepTime = -1;
			for (final WorkSource source : allSources) {
				
				long now = System.currentTimeMillis();
				long lag = now - source.getLastBlockCheckTime();
				long diff;
				
				
				if (lag > source.getMaxIntervalBetweenBlockCheck()) {
					diff = source.getMaxIntervalBetweenBlockCheck();
					if (Res.isDebug()) {
						// Res.logInfo("Calling " +
						// JsonRpcRequest.METHOD_GETBLOCKNUMBER + " on source ["
						// + source.getName() + "] " + new Date());
					}

					// perhaps make this a configurable option. It's a good
					// fallback and doesn't
					// really add load if you increase the interval.
					// if (!source.isNativeLongpoll() ||
					// source.isNativeLongpollVerificationEnabled())
					if (!source.isNativeLongpoll() || source.isNativeLongpollVerificationEnabled()) {
						if (Res.isTrace()) {
							Res.logTrace(Res.TRACE_BLOCKMON_FIRE_CHECK, "Firing timed block check for source: " + source.getName() + " overdue by " + (lag - source.getMaxIntervalBetweenBlockCheck() + "ms"));
						}
						fireBlockCheck(source, now, client, false);
					}
				} else {
					diff = source.getMaxIntervalBetweenBlockCheck() - lag;
					if (Res.isTrace()) {
						Res.logTrace(Res.TRACE_BLOCKMON_FIRE_CHECK, "Sleeping timed block check cycle for source: " + source.getName() + " " + diff + "ms");
					}
				}
				if (sleepTime == -1 || diff < sleepTime)
					sleepTime = diff;
			}
			try {
				synchronized (this) {
					wait(sleepTime < 0 ? 10 : sleepTime);
				}
			} catch (InterruptedException e) {
			}
		}
	}

	ThreadPoolExecutor longpollDispatchExecutor = (ThreadPoolExecutor) Executors.newFixedThreadPool(20,
			new ThreadFactory() {

				int count = 0;

				@Override
				public Thread newThread(Runnable r) {
					Thread t = new Thread(r, "lp-dispatch-" + count++);
					t.setDaemon(true);
					return t;
				}
			});

	ThreadPoolExecutor longpollNotifyClientsExecutor = new ThreadPoolExecutor(1, 10, 10L, TimeUnit.MINUTES,
            new SynchronousQueue<Runnable>(),
			new ThreadFactory() {

				@Override
				public Thread newThread(Runnable r) {
					Thread t = new Thread(r, "notify-lp-clients-executor");
					t.setDaemon(true);
					return t;
				}

			});

	private class NotifyLongpollClientsTask implements Runnable {

		List<LongpollContinuation> waitLocks;
		Integer completed = 0;

		Integer deadConnections = 0;
		
		public NotifyLongpollClientsTask(String name, List<LongpollContinuation> waitLocks) {
			this.waitLocks = waitLocks;

		}

		public void run() {
			long start = System.currentTimeMillis();
			int count = waitLocks.size();
			if (Res.isDebug()) {
				Res.logInfo("Starting longpoll delivery for : " + count + " clients");
			}
			while (!waitLocks.isEmpty()) {
				boolean workAvailable = true;
				// JsonRpcResponse lastWork = null;

				// Queue for any works that we failed to send for some reason.
				// We'll recycle then for a later request as it's probably
				// faster than getting another one from the daemon.
				// We're greenie tree huggers here in psj land.
				final ArrayDeque<JsonRpcResponse> recycled = new ArrayDeque();

				while (workAvailable && !waitLocks.isEmpty()) {
					final AtomicInteger available = new AtomicInteger(proxy.getWorkAvailable());

					if (available.get() > 0) {
						final LongpollContinuation waitLock = waitLocks.remove(waitLocks.size() - 1);

						waitLock.worker.removeLongpoll();

						if (Res.isDebug()) {
							// Res.logInfo("LP response to worker: " +
							// waitLock.worker.getUsername() + " cont: " +
							// waitLock.continuation.toString().replace("org.eclipse.jetty.server.",
							// ""));
						}

						JsonRpcResponse response;
						try {

							synchronized (recycled) {
								response = recycled.pollFirst();
							}
							if (response != null) {
								// we have to do this manually because it's
								// normally done as part of
								// proxy.handleRequest(request, worker)
								waitLock.worker.registerWorkDelivered(1);

							} else {
								response = proxy.handleRequest(waitLock.request, waitLock.worker);
								available.decrementAndGet();
							}

							if (response == null && Res.isDebug()) {
								Res.logDebug("Could not get work from WorkProxy for longpoll inside timeout.  Sending empty response");
							}

							final JsonRpcResponse finalResponse = response;

							longpollDispatchExecutor.execute(new Runnable() {
								public void run() {
									try {
										if (!longpollHandler.completeLongpoll(waitLock, finalResponse)) {
											synchronized (recycled) {
												recycled.addLast(finalResponse);
												deadConnections++;
											}
											
										} 
									} catch (Exception e) {
										Res.logException(e);
										synchronized (recycled) {
											recycled.addLast(finalResponse);
											deadConnections++;
										}
									}
									synchronized (completed) {
										completed++;
									}
								}
							});
							// longpollHandler.completeLongpoll(waitLock,
							// response);

						} catch (JsonRpcResponseException e1) {
							Res.logException(e1);
						}
					}
					workAvailable = available.get() > 0;
				}
				try {
					Thread.sleep(1);
				} catch (InterruptedException e) {
				}
			}

			// longpollDispatchExecutor.shutdown();
			// try {
			// longpollDispatchExecutor.awaitTermination(30000,
			// TimeUnit.MILLISECONDS);
			// } catch (InterruptedException e) {
			// // TODO Auto-generated catch block
			// e.printStackTrace();
			// }

			boolean complete = false;
			boolean timeout = false;
			boolean zeroCount = false;
			
			while (!complete && !timeout && !zeroCount) {
				try {
					Thread.sleep(1);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				complete = completed >= count;
				if (System.currentTimeMillis() > start + 500 && longpollDispatchExecutor.getActiveCount() == 0)
					zeroCount = true;
				if (System.currentTimeMillis() > start + 10000 || shutdown)
					timeout = true;
			}

			if (Res.isDebug()) {
				float time = System.currentTimeMillis() - start;
				if (time != 0) {
					float rate = 1000 * count / time;
					NumberFormat nf = NumberFormat.getInstance();
					nf.setMaximumFractionDigits(2);
					if (timeout)
						Res.logInfo("Dispatched " + completed + " LP responses in " + time + "ms (" + nf.format(rate) + "/sec). Found " + deadConnections + " dead connections. - TIMEOUT was invoked, stats may not be accurate. " + completed + "/" + count);
					else
						Res.logInfo("Dispatched " + completed + " LP responses in " + time + "ms (" + nf.format(rate) + "/sec). Found " + deadConnections + " dead connections. " + completed + "/" + count);					
				}
			}

			// start closing bad longpoll connections. 1st we'll have a little
			// sleep
			// to give the server time to get all it's post-block-change tasks
			// done
			try {
				Thread.sleep(10000);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			List<LongpollContinuation> badWaitLocks = longpollHandler.flushBadQueue();
			Res.logDebug("Closing " + badWaitLocks.size() + " bad longpoll connections from last block");
			start = System.currentTimeMillis();
			for (LongpollContinuation waitLock : badWaitLocks) {
				waitLock.worker.removeBadLongpoll();
				HttpServletResponse resp = (HttpServletResponse) waitLock.continuation.getServletResponse();
				resp.setHeader("Connection", "close");
				try {
					waitLock.continuation.complete();
				} catch (Exception e) {
				}
				waitLock.complete = true;
				try {
					Thread.sleep(5);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			long time = System.currentTimeMillis() - start;
			Res.logDebug("Finished closing bad longpoll connections from last block in " + time
					+ "ms (we took it easy).");

		}

	}

	public int closeLongpollConnectionsAndShutdownThreadPool() {
		longpollDispatchExecutor.shutdownNow();
		longpollNotifyClientsExecutor.shutdownNow();

		List<LongpollContinuation> waitLocks = longpollHandler.flushQueue();
		for (LongpollContinuation waitLock : waitLocks) {
			waitLock.worker.removeLongpoll();
			HttpServletResponse resp = (HttpServletResponse) waitLock.continuation.getServletResponse();
			resp.setHeader("Connection", "close");
			try {
				waitLock.continuation.complete();
			} catch (Exception e) {
			}
			waitLock.complete = true;
		}

		waitLocks = longpollHandler.flushBadQueue();
		for (LongpollContinuation waitLock : waitLocks) {
			waitLock.worker.removeBadLongpoll();
			HttpServletResponse resp = (HttpServletResponse) waitLock.continuation.getServletResponse();
			resp.setHeader("Connection", "close");
			try {
				waitLock.continuation.complete();
			} catch (Exception e) {
			}
			waitLock.complete = true;
		}

		return waitLocks.size();
	}

}
