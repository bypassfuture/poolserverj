package com.shadworld.poolserver.source;

import gnu.trove.map.hash.TObjectLongHashMap;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.text.NumberFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.json.JSONException;

import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import com.shadworld.jsonrpc.JsonRpcClient;
import com.shadworld.jsonrpc.JsonRpcRequest;
import com.shadworld.poolserver.BlockChainTracker;
import com.shadworld.poolserver.LIFOLinkedHashMap;
import com.shadworld.poolserver.WorkProxy;
import com.shadworld.poolserver.WorkSourceEntry;
import com.shadworld.poolserver.conf.Conf;
import com.shadworld.poolserver.conf.Res;
import com.shadworld.poolserver.entity.UniquePortionString;
import com.shadworld.poolserver.entity.Work;
import com.shadworld.poolserver.source.daemonhandler.DaemonHandler;
import com.shadworld.poolserver.source.daemonhandler.JsonRpcDaemonHandler;
import com.shadworld.poolserver.source.daemonhandler.SingleWorkExchange;
import com.shadworld.poolserver.source.daemonhandler.WorkExchange;
import com.shadworld.poolserver.stats.Stats;
import com.shadworld.util.MovingAverage;
import com.shadworld.utils.L;

public abstract class WorkSource {

	private final BlockChainTracker blockTracker;
	final SourceState state;
	final JsonRpcDaemonHandler handler;

	final ArrayDeque<WorkEntry> cache = new ArrayDeque();
	final ArrayDeque workAvailableLock = new ArrayDeque();

	// initial capacity of duplicateCheckSet needs to be a number larger than
	// number of works received
	// from upstream per second.
	// final LIFOLinkedHashMap<String, Object> duplicateCheckSet = new
	// LIFOLinkedHashMap(30000);
	// final HashSet<String> duplicateCheckSet = new HashSet(30000);
	final TObjectLongHashMap<UniquePortionString> duplicateCheckSet = new TObjectLongHashMap();
	final DuplicateRetensionPolicy retensionPolicy = new DuplicateRetensionPolicy();

	final MovingAverage rejectRate = new MovingAverage(10000);

	final private Object getWorkLock = new Object();
	//final Object swapClientLock = new Object();

	String name;

	private boolean rewriteTarget = true;

	int minConcurrentDownloadRequests = 1;
	int concurrentDownloadRequests = 10;
	int maxConcurrentDownloadRequests = 10;

	int currentAskRate = 10;

	int maxConcurrentUpstreamSubmits = 5;

	long maxWorkAgeToFlush = 20000;

	long minIntervalBetweenHttpRequests = 0;
	long currentIntervalBetweenHttpRequests = 0;
	long maxIntervalBetweenHttpRequests = 100;

	long minIntervalBetweenHttpRequestsWhenFrantic = 0;

	int maxCacheSize = 100;
	int currentCacheSize = 50;
	int minCacheSize = 1;

	boolean dynamicCacheSizing = false;

	private int cacheWaitTimeout = 3000;

	boolean delayInProgress = false; // should only be touched by work
										// fetcher handler
	int lastDelayUntilNextRequest = 0;
	int delayUntilNextRequest = 0;

	protected boolean supportUpstreamLongPoll = false;
	private boolean longPollCheckDone = false;
	private String longPollUrl;
	
	long myCurrentBlock = 0;
	long lastBlockCheckTime = System.currentTimeMillis();
	long lastBlockChange = System.currentTimeMillis();
	boolean acceptNextPrevBlockHash = true;
	String prevBlockHash = null;
	boolean unSyncedBlockNum = false;
	
	int maxIntervalBetweenBlockCheck = 100;
	boolean nativeLongpollBlockNumLocked = false;
	boolean nativeLongpoll = false;
	boolean nativeLongpollVerificationEnabled = true;
	String nativeLongpollPassphrase;
	HashSet<String> nativeLongpollAllowedHosts = new HashSet();
	

	final private String url;
	//protected JsonRpcClient getworkClient;
	final String username;
	final String password;
	final private int weighting;
	private double weightingPerc = -1;

	boolean franticMode;

	private final WorkFetcherThread workFetcherThread;
	LongpollThread longpollThread;
	final private WorkProxy proxy;

	public WorkSource(WorkProxy proxy, String name, String url, String username, String password, int weighting) {
		super();
		this.proxy = proxy;
		this.name = name;
		this.url = url;
		this.username = username;
		this.password = password;
		this.weighting = weighting;
		state = new SourceState(this);
		workFetcherThread = new WorkFetcherThread(this, name + "-work-fetcher");
		handler = new JsonRpcDaemonHandler(this);
		blockTracker = proxy.getBlockTracker();
	}

	public void start() {
		handler.start();
		getWorkFetcherThread().start();
		currentCacheSize = dynamicCacheSizing ? (maxCacheSize + minCacheSize) / 2 : maxCacheSize;
		setConcurrentDownloadRequests((maxConcurrentDownloadRequests + minConcurrentDownloadRequests) / 2);

	}

	public void startLongpollThread() {
		if (isSupportUpstreamLongPoll()) {
			longpollThread.start();
		}
	}

//	JsonRpcClient initClient() {
//		synchronized (swapClientLock) {
//			getworkClient = new JsonRpcClient(isSupportUpstreamLongPoll(), url, username, password);
//			QueuedThreadPool pool = new QueuedThreadPool();
//			pool.setName("HttpClient-" + workFetcherThread.getName());
//			pool.setDaemon(true);
//			getworkClient.getClient().setThreadPool(pool);
//			getworkClient.getClient().setMaxConnectionsPerAddress(concurrentDownloadRequests);
//
//		}
//		return getworkClient;
//	}

	public void shutdown() {
		getWorkFetcherThread().shutdown();
		if (longpollThread != null)
			longpollThread.shutdown();
		try {
			handler.shutdown();
		} catch (Exception e) {
		}
		synchronized (cache) {
			cache.notifyAll();
		}
	}

	/**
	 * notifies this source that a block change has happened. The source should
	 * immediately flush it's cached getworks and begin refilling it's cache
	 * from the endpoint.
	 * 
	 * @param currentBlock
	 */
	public void notifyBlockChange(long currentBlock) {
		synchronized (cache) {
			cache.clear();
			// duplicateCheckSet.clear();
			state.upstreamRequestsSinceBlockChange = 0;
			lastBlockChange = System.currentTimeMillis();
			if (myCurrentBlock != currentBlock) {
				//another source found the block and we need to catch up, 
				//so we'll pause work fetching until we do
				unSyncedBlockNum = true;
			}
		}
	}
	
	/**
	 * called after a longpoll if this source wasn't the first to find the new block.
	 */
	public void resyncToBlockTracker() {
		synchronized (cache) {
			//ensure we don't have any work from previous block.
			cache.clear();
			
			//make sure the next work knows to populate prevBlockHash
			prevBlockHash = null;
			acceptNextPrevBlockHash = true;
			
			//restart the work fetcher
			unSyncedBlockNum = false;
			nativeLongpollBlockNumLocked = false;
			cache.notifyAll();
		}
		synchronized (workFetcherThread) {
			workFetcherThread.notifyAll();
		}
	}
	
	public synchronized Long nativeLongpollIncrementBlockNum() {
		if (nativeLongpollBlockNumLocked) {
			if (Res.isTrace()) {
				Res.logTrace(Res.TRACE_BLOCKMON, name + " blocked nativeLongpollIncrementBlocknum");
			}
			return myCurrentBlock;
		}
		nativeLongpollBlockNumLocked = true;
		myCurrentBlock++;
		return myCurrentBlock;
	}

	public int trimDuplicateMap() {
		retensionPolicy.compareTime = System.currentTimeMillis() - 10000;

		synchronized (duplicateCheckSet) {
			int size = duplicateCheckSet.size();
			duplicateCheckSet.retainEntries(retensionPolicy);
			return size - duplicateCheckSet.size();
		}
	}
	
	public void processSingleWork(Work work, Long reportedBlock, int numRequested) {
		
		if (isBitcoinDaemon() && (!nativeLongpoll || isNativeLongpollVerificationEnabled()) ) {
			
			//handling for using prev block hash as a block change indicator.
			if (acceptNextPrevBlockHash) {
				synchronized (cache) {
					// recheck inside sync block
					if (acceptNextPrevBlockHash) {
						String prev = work.getData();
						if (prev != null && prev.length() > 72) {
							prev = prev.substring(8, 72);
							prevBlockHash = prev;
							acceptNextPrevBlockHash = false;
							if (Res.isTrace(Res.TRACE_BLOCKMON)) {
								Res.logInfo("Repopulating prev_block_hash for source: [" + getName() + "] - " + prev);
							}
						}
					}
				}
			} else if (prevBlockHash != null && !isPrevBlockHashMatch(work)
					&& System.currentTimeMillis() > lastBlockCheckTime + 10) {
				lastBlockCheckTime = System.currentTimeMillis();
				if (Res.isTrace(Res.TRACE_BLOCKMON)) {
					Res.logInfo("Detected possible block change using prev_block_hash on source: [" + getName() + "]");
				}
				getBlockTracker().fireBlockCheck(this, false);
				String prev = work.getData();
				if (prev != null && prev.length() > 72) {
					prev = prev.substring(8, 72);
					prevBlockHash = prev;
					acceptNextPrevBlockHash = false;
					if (Res.isTrace(Res.TRACE_BLOCKMON)) {
						Res.logInfo("Repopulating prev_block_hash for source: [" + getName() + "] - " + prev);
					}
				}
			}
		}

		WorkEntry entry = new WorkEntry(this, work, System.currentTimeMillis(), reportedBlock);
		cacheEntry(entry);
		state.registerIncomingWork(1, numRequested);
		Stats.get().registerUpstreamRequestSuccess(this);
	}
	
	/**
	 * checks first 5 chars for a match.
	 * 
	 * @param work
	 * @return
	 */
	private boolean isPrevBlockHashMatch(Work work) {
		String prev = work.getData();
		if (prev != null && prev.length() > 72) {
			for (int i = 0; i < 5; i++) {
				if (prev.charAt(i + 8) != prevBlockHash.charAt(i))
					return false;
			}
			return true;
		}
		return false;
	}

	// /**
	// *
	// * @return true if longpoll supported
	// */
	// public abstract boolean isSupportLongPoll();

	/**
	 * 
	 * @return true if upstream server longpoll supported
	 */
	public abstract boolean isSupportUpstreamLongPoll();

	/**
	 * 
	 * @return true only if this source is a direct link to a bitcoin daemon
	 *         server. This source can be used to proxy any other JSON-RPC
	 *         method calls that are not specifically handled by the endpoint.
	 */
	public abstract boolean isBitcoinDaemon();

	/**
	 * @return the rewriteDifficulty
	 */
	public abstract boolean isRewriteDifficulty();

	/**
	 * @return the rewriteDifficultyTarget
	 */
	public abstract String getRewriteDifficultyTarget();

	/**
	 * gets a single unit of work. If the cache is empty then blocks until a
	 * work unit is available.
	 * 
	 * @return may return null if the work fetcher is putting invalid work into
	 *         the queue.
	 */
	public Work getWork() {
		return getWork(1);
	}

	private void notifyCache() {
		synchronized (cache) {
			cache.notifyAll();
		}
	}

	/**
	 * private implementation allowing passing of numRequested when a getWorks
	 * call fails to find any work in cache
	 * 
	 * @param numRequested
	 * @return
	 */
	protected Work getWork(int numRequested) {
		WorkEntry entry;
		synchronized (getWorkLock) {
			synchronized (cache) {
				entry = cache.poll();
				if (entry != null)
					cache.notifyAll();
			}
			if (entry != null && validateEntry(entry)) {
				Stats.get().registerWorkAvailableImmediately(WorkSource.this, entry, numRequested, 1, cache.size());
				state.registerValidCacheRetrieval(cache.size() - numRequested + 1, entry.getTime);
				state.registerWorkRequest(numRequested, 1);
				return entry.work;
			}
			Stats.get().registerWorkNotAvailableImmediately(WorkSource.this, numRequested, 0);
			long start = System.currentTimeMillis();
			while (entry == null && System.currentTimeMillis() < start + cacheWaitTimeout) {
				if (entry == null) {
					Object waitLock = new Object();
					workAvailableLock.offerLast(waitLock);
					try {
						synchronized (waitLock) {
							waitLock.wait(cacheWaitTimeout - System.currentTimeMillis() + start);
						}
					} catch (InterruptedException e) {
						return null;
					}
				}
				synchronized (cache) {
					entry = cache.poll();
					if (entry != null)
						cache.notifyAll();
				}
				if (entry == null || !validateEntry(entry)) {
					entry = null;

				}
			}
		}
		if (entry == null) {
			Stats.get().registerWorkNotAvailableAfterTimeout(WorkSource.this, numRequested);
		} else {
			Stats.get().registerWorkAvailableAfterWait(WorkSource.this, numRequested, 1, cache.size());
			state.registerValidCacheRetrieval(cache.size() - numRequested + 1, entry.getTime);
			state.registerWorkRequest(numRequested, 1);
		}
		return entry == null ? null : entry.work;
	}

	/**
	 * @return the currentBlock
	 */

	boolean validateEntry(WorkEntry entry) {
		if (entry == null) {
			L.println("null work entry in cache, how did it get there?");
			return false;
		}
		// boolean expired = entry.getTime > System.currentTimeMillis() -
		// maxWorkAgeToFlush;

		// L.println("eb: " + entry.blockNumber + " cb: " + currentBlock);
		// L.println("ebt: " + entry.getTime + " ct: " +
		// System.currentTimeMillis() + " exp: " + Conf.getWorkerCacheExpiry() +
		// " comp: " + (System.currentTimeMillis() -
		// Conf.getWorkerCacheExpiry()));
		return entry.blockNumber == getBlockTracker().getCurrentBlock()
				&& entry.getTime > System.currentTimeMillis() - maxWorkAgeToFlush;
	}

	/**
	 * 
	 * @return the latest block number this source has received. For latest
	 *         block received by any source use
	 *         BlockChainTracker.getCurrentBlock();
	 */
	public long getMyCurrentBlock() {
		return myCurrentBlock;
	}

	void cacheEntry(WorkEntry entry) {
		//reject entries is the source is out of sync
		if (entry == null || unSyncedBlockNum)
			return;
		// TODO remove this, it for debug and it's a memory leak
		// if (Res.isDebug()) {
		// WorkSourceEntry e = new WorkSourceEntry(this, entry.getWork(),
		// System.currentTimeMillis(),
		// getMyCurrentBlock());
		// proxy.allWork.put(entry.getWork().getDataUniquePortion(), e);
		// }
		Stats.get().registerWorkReceived(WorkSource.this, entry);
		synchronized (cache) {
			UniquePortionString key = entry.work.getDataUniquePortion();
			// if (duplicateCheckSet.containsKey(key)) {
			boolean duplicate;
			synchronized (duplicateCheckSet) {
				duplicate = duplicateCheckSet.put(key, System.currentTimeMillis()) != 0;
			}
			if (duplicate) {
				Stats.get().registerWorkDuplicate(this, entry, true);
				rejectRate.addValue(100);
			} else {
				// duplicateCheckSet.put(key, null);
				Stats.get().registerWorkDuplicate(this, entry, false);
				getBlockTracker().reportDifficultyTarget(entry);
				rejectRate.addValue(0);
				if (entry.blockNumber == getBlockTracker().getCurrentBlock()) {
					// rewrite target
					if (rewriteTarget) {
						entry.work.setTarget(Res.getEasyDifficultyTargetAsString());
					}
					cache.offerLast(entry);
				}
			}
		}
	}

	protected WorkExchange buildContentExchangeForSubmit() {
		throw new NotImplementedException();
		// return new SingleWorkExchange(this, workFetcherThread);
	}

	/**
	 * @return the maxConcurrentUpstreamSubmits
	 */
	public int getMaxConcurrentUpstreamSubmits() {
		return maxConcurrentUpstreamSubmits;
	}

	/**
	 * @param maxConcurrentUpstreamSubmits
	 *            the maxConcurrentUpstreamSubmits to set
	 */
	public void setMaxConcurrentUpstreamSubmits(int maxConcurrentUpstreamSubmits) {
		this.maxConcurrentUpstreamSubmits = maxConcurrentUpstreamSubmits;
		handler.swapShareSubmitClient();
	}

	/**
	 * @return the currentAskRate
	 */
	public int getCurrentAskRate() {
		return currentAskRate;
	}

	/**
	 * @param currentAskRate
	 *            the currentAskRate to set
	 */
	public void setCurrentAskRate(int currentAskRate) {
		int max = Conf.get().getMaxMultiWork();
		if (currentAskRate > max)
			this.currentAskRate = max;
		else if (currentAskRate < 2)
			this.currentAskRate = 2;
		else
			this.currentAskRate = currentAskRate;
	}

	/**
	 * this will force the current JsonRpcClient (and it's jetty http getworkClient) to
	 * be discarded and replaced with a new one.
	 * 
	 * @param concurrentDownloadRequests
	 */
	public void setConcurrentDownloadRequests(int concurrentDownloadRequests) {
		int oldValue = this.concurrentDownloadRequests;
		if (this.concurrentDownloadRequests > this.maxConcurrentDownloadRequests) {
			this.concurrentDownloadRequests = this.maxConcurrentDownloadRequests;
		} else if (this.concurrentDownloadRequests < this.minConcurrentDownloadRequests) {
			this.concurrentDownloadRequests = this.minConcurrentDownloadRequests;
		} else {
			this.concurrentDownloadRequests = concurrentDownloadRequests;
		}
		if (oldValue != this.concurrentDownloadRequests) {
			handler.swapGetworkClient();
		}
	}

	/**
	 * @return the minConcurrentDownloadRequests
	 */
	public int getMinConcurrentDownloadRequests() {
		return minConcurrentDownloadRequests;
	}

	/**
	 * @param minConcurrentDownloadRequests
	 *            the minConcurrentDownloadRequests to set
	 */
	public void setMinConcurrentDownloadRequests(int minConcurrentDownloadRequests) {
		this.minConcurrentDownloadRequests = minConcurrentDownloadRequests;
	}

	/**
	 * @return the maxConcurrentDownloadRequests
	 */
	public int getMaxConcurrentDownloadRequests() {
		return maxConcurrentDownloadRequests;
	}

	/**
	 * @param maxConcurrentDownloadRequests
	 *            the maxConcurrentDownloadRequests to set
	 */
	public void setMaxConcurrentDownloadRequests(int maxConcurrentDownloadRequests) {
		this.maxConcurrentDownloadRequests = maxConcurrentDownloadRequests;
		if (!dynamicCacheSizing)
			concurrentDownloadRequests = maxConcurrentDownloadRequests;
	}

	/**
	 * @return the concurrentDownloadRequests
	 */
	public int getConcurrentDownloadRequests() {
		return concurrentDownloadRequests;
	}

	/**
	 * @return the url
	 */
	public String getUrl() {
		return url;
	}

	// /**
	// * @param url
	// * the url to set
	// */
	// public void setUrl(String url) {
	// this.url = url;
	// }

	/**
	 * @return the longPollUrl
	 */
	public String getLongPollUrl() {
		return longPollUrl;
	}

	/**
	 * @return the weighting which is used to calculate how often this source is
	 *         used for single getworks.
	 */
	public int getWeighting() {
		return weighting;
	}

	// /**
	// * @param weighting
	// * the weighting to set
	// */
	// public void setWeighting(int weighting) {
	// this.weighting = weighting;
	// }

	/**
	 * @return the weightingPerc
	 */
	public double getWeightingPerc() {
		return weightingPerc;
	}

	/**
	 * @param weightingPerc
	 *            the weightingPerc to set
	 */
	public void setWeightingPerc(double weightingPerc) {
		this.weightingPerc = weightingPerc;
	}

//	/**
//	 * @return the getworkClient
//	 */
//	public JsonRpcClient getClient() {
//		return getworkClient;
//	}

	/**
	 * @return the name
	 */
	public String getName() {
		return name;
	}

	/**
	 * @param name
	 *            the name to set
	 */
	public void setName(String name) {
		this.name = name;
	}

	/**
	 * @return the maxWorkAgeToFlush
	 */
	public long getMaxWorkAgeToFlush() {
		return maxWorkAgeToFlush;
	}

	/**
	 * @param maxWorkAgeToFlush
	 *            the maxWorkAgeToFlush to set
	 */
	public void setMaxWorkAgeToFlush(long maxWorkAgeToFlush) {
		this.maxWorkAgeToFlush = maxWorkAgeToFlush;
	}

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
	 * @return the currentIntervalBetweenHttpRequests
	 */
	public long getIntervalBetweenHttpRequests() {
		return currentIntervalBetweenHttpRequests;
	}

	/**
	 * enforces bounds between minIntervalBetweenHttpRequests and
	 * maxIntervalBetweenHttpRequests
	 * 
	 * @param intervalBetweenHttpRequests
	 *            the intervalBetweenHttpRequests to set
	 */
	public void setIntervalBetweenHttpRequests(long intervalBetweenHttpRequests) {
		if (intervalBetweenHttpRequests > maxIntervalBetweenHttpRequests)
			this.currentIntervalBetweenHttpRequests = maxIntervalBetweenHttpRequests;
		else if (intervalBetweenHttpRequests < minIntervalBetweenHttpRequests)
			this.currentIntervalBetweenHttpRequests = minIntervalBetweenHttpRequests;
		else
			this.currentIntervalBetweenHttpRequests = intervalBetweenHttpRequests;
	}

	/**
	 * @return the maxIntervalBetweenHttpRequests
	 */
	public long getMaxIntervalBetweenHttpRequests() {
		return maxIntervalBetweenHttpRequests;
	}

	/**
	 * @param maxIntervalBetweenHttpRequests
	 *            the maxIntervalBetweenHttpRequests to set
	 */
	public void setMaxIntervalBetweenHttpRequests(long maxIntervalBetweenHttpRequests) {
		this.maxIntervalBetweenHttpRequests = maxIntervalBetweenHttpRequests;
	}

	/**
	 * @return the minIntervalBetweenHttpRequestsWhenFrantic
	 */
	public long getMinIntervalBetweenHttpRequestsWhenFrantic() {
		return minIntervalBetweenHttpRequestsWhenFrantic;
	}

	/**
	 * @param minIntervalBetweenHttpRequestsWhenFrantic
	 *            the minIntervalBetweenHttpRequestsWhenFrantic to set
	 */
	public void setMinIntervalBetweenHttpRequestsWhenFrantic(long minIntervalBetweenHttpRequestsWhenFrantic) {
		this.minIntervalBetweenHttpRequestsWhenFrantic = minIntervalBetweenHttpRequestsWhenFrantic;
	}

	/**
	 * @return the currentCacheSize
	 */
	public int getMaxCacheSize() {
		return maxCacheSize;
	}

	/**
	 * @param currentCacheSize
	 *            the currentCacheSize to set
	 */
	public void setMaxCacheSize(int maxCacheSize) {
		this.maxCacheSize = maxCacheSize;
		if (!dynamicCacheSizing)
			this.currentCacheSize = maxCacheSize;
		trimCache();
	}

	private void trimCache() {
		while (cache.size() > this.currentCacheSize) {
			synchronized (cache) {
				cache.poll();
			}
		}
		synchronized (cache) {
			cache.notifyAll();
		}
	}

	/**
	 * @return the minCacheSize
	 */
	public int getMinCacheSize() {
		return minCacheSize;
	}

	/**
	 * @param minCacheSize
	 *            the minCacheSize to set
	 */
	public void setMinCacheSize(int minCacheSize) {
		this.minCacheSize = minCacheSize;
	}

	/**
	 * @return the current limit of cache size NOT the current number of
	 *         entries.
	 */
	public int getCurrentCacheSize() {
		return currentCacheSize;
	}

	/**
	 * @return the cacheWaitTimeout
	 */
	public int getCacheWaitTimeout() {
		return cacheWaitTimeout;
	}

	/**
	 * @param cacheWaitTimeout
	 *            the cacheWaitTimeout to set
	 */
	public void setCacheWaitTimeout(int cacheWaitTimeout) {
		this.cacheWaitTimeout = cacheWaitTimeout;
	}

	/**
	 * @return the username
	 */
	public String getUsername() {
		return username;
	}

	// /**
	// * @param username
	// * the username to set
	// */
	// public void setUsername(String username) {
	// this.username = username;
	// }

	/**
	 * @return the lastBlockCheckTime
	 */
	public long getLastBlockCheckTime() {
		return lastBlockCheckTime;
	}

	/**
	 * @param lastBlockCheckTime
	 *            the lastBlockCheckTime to set
	 */
	public void setLastBlockCheckTime(long lastBlockCheckTime) {
		this.lastBlockCheckTime = lastBlockCheckTime;
	}

	/**
	 * @param myCurrentBlock
	 *            the myCurrentBlock to set
	 */
	public void setMyCurrentBlock(long myCurrentBlock) {
		this.myCurrentBlock = myCurrentBlock;
	}

	/**
	 * @return the password
	 */
	public String getPassword() {
		return password;
	}

	// /**
	// * @param password
	// * the password to set
	// */
	// public void setPassword(String password) {
	// this.password = password;
	// }

	/**
	 * @return the franticMode
	 */
	public boolean isFranticMode() {
		return franticMode;
	}

	/**
	 * @param franticMode
	 *            the franticMode to set
	 */
	public void setFranticMode(boolean franticMode) {
		this.franticMode = franticMode;
	}
	
	public Set<String> getNativeLongpollAllowedHosts() {
		return nativeLongpollAllowedHosts;
	}
	
	public boolean isAllowedNativeLongpollAddress(InetSocketAddress addr) {
		if (addr == null) {
			Res.logDebug("Null socket address for native longpoll on source: " + name);
			return false;
		}
		return nativeLongpollAllowedHosts.contains(addr.getAddress().getCanonicalHostName()) || nativeLongpollAllowedHosts.contains(addr.getAddress().getHostAddress());
	}

	/**
	 * @return the nativeLongpollVerificationEnabled
	 */
	public boolean isNativeLongpollVerificationEnabled() {
		return nativeLongpollVerificationEnabled;
	}

	/**
	 * @param nativeLongpollVerificationEnabled the nativeLongpollVerificationEnabled to set
	 */
	public void setNativeLongpollVerificationEnabled(boolean nativeLongpollVerificationEnabled) {
		this.nativeLongpollVerificationEnabled = nativeLongpollVerificationEnabled;
	}

	/**
	 * @return the nativeLongpollPassphrase
	 */
	public String getNativeLongpollPassphrase() {
		return nativeLongpollPassphrase;
	}

	/**
	 * @param nativeLongpollPassphrase the nativeLongpollPassphrase to set
	 */
	public void setNativeLongpollPassphrase(String nativeLongpollPassphrase) {
		this.nativeLongpollPassphrase = nativeLongpollPassphrase;
	}

	/**
	 * @return the nativeLongpoll
	 */
	public boolean isNativeLongpoll() {
		return nativeLongpoll;
	}

	/**
	 * @param nativeLongpoll the nativeLongpoll to set
	 */
	public void setNativeLongpoll(boolean nativeLongpoll) {
		this.nativeLongpoll = nativeLongpoll;
	}

	/**
	 * @return the proxy
	 */
	public WorkProxy getProxy() {
		return proxy;
	}

	// /**
	// * @param proxy
	// * the proxy to set
	// */
	// public void setProxy(WorkProxy proxy) {
	// this.proxy = proxy;
	// }

	/**
	 * @return the rejectRate
	 */
	public MovingAverage getRejectRate() {
		return rejectRate;
	}

	public String getCacheStats() {
		int entries = 0;
		long oldest = -1;
		long newest = -1;
		long total = 0;
		synchronized (cache) {
			for (WorkEntry entry : cache) {
				entries++;
				long diff = System.currentTimeMillis() - entry.getTime;
				if (oldest == -1 || oldest < diff)
					oldest = diff;
				if (newest == -1 || newest > diff)
					newest = diff;
				total += diff;
			}
		}
		NumberFormat nf = NumberFormat.getInstance();
		nf.setMaximumFractionDigits(4);
		return "Entries: " + entries + " Oldest: " + oldest + " Newest: " + newest + " Avg: "
				+ (entries == 0 ? 0 : total / entries) + " Reject Rate: " + nf.format(rejectRate.getAvg()) + "%";
	}

	public String getDuplicateCalcString() {
		HashMap<String, WorkEntry> set = new HashMap();
		int duplicates = 0;
		int size;
		int nulls = 0;
		synchronized (cache) {
			size = cache.size();
			for (WorkEntry entry : cache) {
				String data = entry.work.getData();
				WorkEntry other = set.put(data, entry);
				if (data == null)
					nulls++;
				else if (other != null && other.hashCode() != entry.hashCode()) {
					duplicates++;
					try {
						if (entry.hashCode() != other.hashCode()) {
							L.println(entry.work.getObject().toString(4));
							L.println(other.work.getObject().toString(4));
						}
					} catch (JSONException e) {
						Res.logException(e);
					}

				}
			}
		}
		return "size: " + size + " nulls: " + nulls + " duplicates: " + duplicates + " uniques: " + set.size();
	}

	public void handleBadHttpResponse(WorkExchange ex) {
		int status = ex.getResponseStatus();
		int delay = 1000;
		// if (status == 401) {
		//
		// }
		addDownloadDelay(delay);

		state.registerHttpFail(status);
		Stats.get().registerUpstreamRequestFailed(this);
	}

	public synchronized void handleConnectionFail(WorkExchange ex, Throwable x) {
		state.registerConnectFail();
		if (!state.isProbablyOffline())
			return;
		addDownloadDelay(1000);
		if (Res.isDebug() && !delayInProgress) {
			Res.logInfo("Connection fail to source: " + name + " reason: \"" + x.getMessage() + "\" retry in "
					+ delayUntilNextRequest + "ms");

		} else if (!delayInProgress && delayUntilNextRequest < 1001) {
			Res.logInfo("Connection fail to source: " + name + " retry in " + delayUntilNextRequest + "ms");
		}
	}

	private void addDownloadDelay(int delay) {
		if (!delayInProgress && delayUntilNextRequest == 0) {
			delayUntilNextRequest = lastDelayUntilNextRequest == 0 ? delay : lastDelayUntilNextRequest + delay;
			if (delayUntilNextRequest > 4000)
				delayUntilNextRequest = 4000;
		}
	}

	public void join() {
		try {
			workFetcherThread.join();
		} catch (InterruptedException e) {
		}
	}

	public int getWorksAvailable() {
		return cache.size();
	}

	public SourceState getState() {
		return state;
	}

	public String toString() {
		return getClass().getSimpleName() + "[" + name + "]";
	}

	public boolean isLongPollCheckDone() {
		return longPollCheckDone;
	}

	public void setLongPollCheckDone(boolean longPollCheckDone) {
		this.longPollCheckDone = longPollCheckDone;
	}

	public void setLongPollUrl(String longPollUrl) {
		this.longPollUrl = longPollUrl;
	}

	public BlockChainTracker getBlockTracker() {
		return blockTracker;
	}

	/**
	 * @return the maxIntervalBetweenBlockCheck
	 */
	public int getMaxIntervalBetweenBlockCheck() {
		return maxIntervalBetweenBlockCheck;
	}

	/**
	 * @param maxIntervalBetweenBlockCheck the maxIntervalBetweenBlockCheck to set
	 */
	public void setMaxIntervalBetweenBlockCheck(int maxIntervalBetweenBlockCheck) {
		this.maxIntervalBetweenBlockCheck = maxIntervalBetweenBlockCheck;
	}
	
	

	public WorkFetcherThread getWorkFetcherThread() {
		return workFetcherThread;
	}

	public DaemonHandler getDaemonHandler() {
		return handler;
	}
	
}
