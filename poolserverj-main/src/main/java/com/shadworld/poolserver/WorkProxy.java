package com.shadworld.poolserver;

import gnu.trove.map.hash.TCustomHashMap;
import gnu.trove.map.hash.THashMap;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.math.BigInteger;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.WordUtils;
import org.eclipse.jetty.server.LocalConnector;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.google.bitcoin.core.PSJBlock;
import com.google.bitcoin.core.NetworkParameters;
import com.google.bitcoin.core.ProtocolException;
import com.google.bitcoin.core.VerificationException;
import com.shadworld.cache.ArrayDequeResourcePool;
import com.shadworld.jsonrpc.JsonRpcRequest;
import com.shadworld.jsonrpc.JsonRpcResponse;
import com.shadworld.jsonrpc.exception.InvalidJsonRpcRequestException;
import com.shadworld.jsonrpc.exception.InvalidParamsException;
import com.shadworld.jsonrpc.exception.InvalidWorkException;
import com.shadworld.jsonrpc.exception.JsonRpcResponseException;
import com.shadworld.jsonrpc.exception.MethodNotFoundException;
import com.shadworld.poolserver.conf.Conf;
import com.shadworld.poolserver.conf.Res;
import com.shadworld.poolserver.entity.FastEqualsSolution;
import com.shadworld.poolserver.entity.UniquePortionString;
import com.shadworld.poolserver.entity.Work;
import com.shadworld.poolserver.entity.Worker;
import com.shadworld.poolserver.logging.WorkRequestLogger;
import com.shadworld.poolserver.logging.ShareEntry;
import com.shadworld.poolserver.logging.ShareSubmitter;
import com.shadworld.poolserver.source.DaemonSource;
import com.shadworld.poolserver.source.WorkSource;
import com.shadworld.poolserver.stats.SourceStats;
import com.shadworld.poolserver.stats.Stats;
import com.shadworld.util.MovingAverage;
import com.shadworld.util.Time;
import com.shadworld.utils.L;

import sun.reflect.generics.reflectiveObjects.NotImplementedException;

public class WorkProxy {

	final PoolServer server;

	final BlockChainTracker blockTracker;

	final ShareSubmitter workResultSubmitter;
	final WorkRequestLogger workRequestLogger;

	final ArrayList<DaemonSource> daemonSources = new ArrayList();
	final ArrayList<WorkSource> nonDaemonSources = new ArrayList();
	final ArrayList<WorkSource> allSources = new ArrayList();
	final ArrayList<WorkSource> allServingSources = new ArrayList();
	final private HashMap<String, WorkSource> sourceByName = new HashMap();

	// private HashMap<Object, WorkSourceEntry> sentBlocksCache = new HashMap();
	// private HashMap<Object, WorkSourceEntry> lastBlocksentBlocksCache;
	// private HashMap<Object, WorkSourceEntry> submittedWork = new HashMap();
	// private HashMap<Object, WorkSourceEntry> lastBlockSubmittedWork;

	// gnu.trove.strategy.HashingStrategy<T> x;
	// TCustomHashMap<K, V> c;

	final private WorkSourceEntryRetensionProcedure workSourceEntryRetensionProcedure = new WorkSourceEntryRetensionProcedure();

	private THashMap<UniquePortionString, WorkSourceEntry> sentBlocksCache = new THashMap();
	private THashMap<UniquePortionString, WorkSourceEntry> lastBlocksentBlocksCache;
	private THashMap<UniquePortionString, WorkSourceEntry> submittedWork = new THashMap();
	private THashMap<UniquePortionString, WorkSourceEntry> lastBlockSubmittedWork;

	private WorkSourceEntrySerializationContainer container;

	// TODO remove this, for debug only
	// public HashMap<String, WorkSourceEntry> allWork = new HashMap();
	// public HashMap<String, WorkSourceEntry> allMappedWork = new HashMap();

	// private HashSet<String> staleWork = new HashSet();

	// config
	private volatile boolean acceptNotifyBlockChange = true;
	private boolean allowBreakWeightingRulesAfterBlockChange = true;

	// extensions and memory management
	private long maxAgeToMapWorkSource = 130000;
	private boolean useCompressedMapKeys = false;
	private boolean enableNonceRange = true;
	private float nonceRangePaddingFactor = 2.0f;
	private boolean enableRollNTime = true;
	private long rollNTimeExpire = 120000;

	// end config

	final SourceUtilisationComparator sourceUtilisationComparator = new SourceUtilisationComparator();
	final SourceAvailableWorkComparator sourceAvailableWorkComparator = new SourceAvailableWorkComparator();
	// private TreeMap<WorkSource, Double> usageMap;
	// private TreeSet<WorkSource> availableSet;

	private boolean shutdown = false;

	public WorkProxy(PoolServer poolServer) {
		this.server = poolServer;
		this.workResultSubmitter = server.shareSubmitter;
		this.workRequestLogger = server.workRequestLogger;
		this.blockTracker = server.blockTracker;
		// usageMap = new TreeMap(new SourceUtilisationComparator());
		// availableSet = new TreeSet(new SourceAvailableWorkComparator());
	}

	protected void mapWorkToSource(WorkSource source, Work work) {
		WorkSourceEntry entry = new WorkSourceEntry(source, System.currentTimeMillis(), source.getMyCurrentBlock());

		// TODO remove this, for debug only and is a memory leak
		// allMappedWork.put(work.getDataUniquePortion(), entry);

		synchronized (sentBlocksCache) {
			// map only the merkleroot + timestamp portion which we've already
			// guarunteed is unique per source within WorkSource
			sentBlocksCache.put(work.getDataUniquePortion(), entry);
			Stats.get().registerWorkMappedToSource(entry);
		}
	}

	public void notifyBlockChange() {
		synchronized (sentBlocksCache) {
			if (lastBlocksentBlocksCache != null) {
				synchronized (lastBlocksentBlocksCache) {
					lastBlocksentBlocksCache = sentBlocksCache;
					sentBlocksCache = new THashMap();
					lastBlockSubmittedWork = submittedWork;
					submittedWork = new THashMap();
				}
			} else {
				lastBlocksentBlocksCache = sentBlocksCache;
				sentBlocksCache = new THashMap();
				lastBlockSubmittedWork = submittedWork;
				submittedWork = new THashMap();
			}
		}
	}

	private JsonRpcResponse validateWork(JsonRpcRequest request, Worker worker) throws JsonRpcResponseException {
		String data = request.getParams().optString(0); // already ensured it's
														// there before this
														// call
		if (data == null || data.length() < 256) {
			Stats.get().registerInvalidWorkSubmitted(request, worker, null);
			throw new InvalidWorkException();
		}
		data = data.toLowerCase();

		// build work;
		Work work = new Work();
		work.setDataOnly(data);

		WorkSourceEntry entry = null;
		boolean putSubmitted = true;
		synchronized (sentBlocksCache) {
			entry = sentBlocksCache.remove(work.getDataUniquePortion());
			if (entry == null) {
				// nothing to put and if it's found in submittedWork it's
				// already there.
				putSubmitted = false;
				entry = submittedWork.get(work.getDataUniquePortion());
				if (entry != null) {
					final FastEqualsSolution solution = new FastEqualsSolution(data);
					if (entry.solutions == null) {
						// this work has only been submitted once before.

						// check against firstSolution
						if (solution.equals(entry.firstSolution)) {
							ShareEntry resultEntry = new ShareEntry(null, worker, request, false, false, "duplicate",
									data, entry.blockNum);
							workResultSubmitter.getShareLogger().submitLogEntry(resultEntry);
							return buildGetWorkSubmitResponse((Integer) request.getId(), false, "duplicate");
						}

						// now we have multiple submits we'll lazily initialise
						// the list of solutions
						// based on this work.

						// use an array list rather than hashset here because we
						// have to do equals() on every entry to guarantee
						// uniqueness
						//
						// FastEqualsSolution should make this very quick and in
						// most cases
						// avoid the need to traverse more than a few chars of
						// each string
						//
						entry.solutions = new ArrayList(10);
						entry.solutions.add(entry.firstSolution);
						entry.solutions.add(solution);
					} else if (entry.solutions.contains(solution)) {
						// this is the 3rd or subsequent time this work has been
						// submitted

						ShareEntry resultEntry = new ShareEntry(null, worker, request, false, false, "duplicate", data,
								entry.blockNum);
						workResultSubmitter.getShareLogger().submitLogEntry(resultEntry);
						return buildGetWorkSubmitResponse((Integer) request.getId(), false, "duplicate");
					} else {
						// this is the 3rd or subsequent time this work has been
						// submitted
						// so we only need to add it to the list
						entry.solutions.add(solution);
					}
				}
			} else {
				// first time this solution has been submitted.
				entry.firstSolution = new FastEqualsSolution(data);
			}

			// get this in the submittedWork cache before we unblock so we can't
			// have duplicates sneaking in.
			// @see
			// https://bitcointalk.org/index.php?topic=33142.msg467844#msg467844
			if (putSubmitted) {
				synchronized (submittedWork) {
					submittedWork.put(work.getDataUniquePortion(), entry);
				}
			}
		}
		if (entry == null && lastBlocksentBlocksCache != null) {
			synchronized (lastBlocksentBlocksCache) {
				entry = lastBlocksentBlocksCache.remove(work.getDataUniquePortion());
				if (entry == null) {
					entry = lastBlockSubmittedWork.get(work.getDataUniquePortion());
				}
			}
			if (entry != null) {
				Stats.get().registerUnknownWorkSubmitted(request, worker);
				ShareEntry resultEntry = new ShareEntry(null, worker, request, false, false, "stale", data,
						entry.blockNum);
				workResultSubmitter.getShareLogger().submitLogEntry(resultEntry);
				return buildGetWorkSubmitResponse((Integer) request.getId(), false, "stale");
			}
		}
		if (entry == null) {

			// debug
			// if (Res.isDebug()) {
			// WorkSourceEntry lowest = null;
			// int lev = -1;
			// for (String s : sentBlocksCache.keySet()) {
			// if (lowest == null)
			// lowest = sentBlocksCache.get(s);
			// else {
			// int l =
			// StringUtils.getLevenshteinDistance(work.getDataUniquePortion(),
			// sentBlocksCache.get(s)
			// .getWork().getDataUniquePortion());
			// if (lev == -1 || l < lev) {
			// lev = l;
			// lowest = sentBlocksCache.get(s);
			// }
			// }
			// }
			// if (lastBlocksentBlocksCache != null) {
			// for (String s : lastBlocksentBlocksCache.keySet()) {
			// if (lowest == null)
			// lowest = lastBlocksentBlocksCache.get(s);
			// else {
			// int l =
			// StringUtils.getLevenshteinDistance(work.getDataUniquePortion(),
			// lastBlocksentBlocksCache.get(s).getWork().getDataUniquePortion());
			// if (lev == -1 || l < lev) {
			// lev = l;
			// lowest = lastBlocksentBlocksCache.get(s);
			// }
			// }
			// }
			// }
			// L.println("Lev: " + lev);
			// L.println("data:    " + work.getDataUniquePortion());
			// L.println("closest: " + lowest.getWork().getDataUniquePortion());
			// // L.println("Found in allMappedWork: " +
			// // allMappedWork.containsKey(work.getDataUniquePortion())
			// // + " Found in allWork: " +
			// // allWork.containsKey(work.getDataUniquePortion()));
			// // L.println("allMappedWork size: " + allMappedWork.size() +
			// // " allWork size: " + allWork.size());
			// L.println("Found in submittedWork: "
			// + submittedWork.containsKey(work.getDataUniquePortion())
			// + " Found in lastBlockSubmittedWork: "
			// + (lastBlockSubmittedWork == null ? false :
			// lastBlockSubmittedWork.containsKey(work
			// .getDataUniquePortion())));
			// L.println("submittedWork size: " + submittedWork.size() +
			// " lastBlockSubmittedWork size: "
			// + (lastBlockSubmittedWork == null ? -1 :
			// lastBlockSubmittedWork.size()));
			// }
			// end debug

			Stats.get().registerUnknownWorkSubmitted(request, worker);
			ShareEntry resultEntry = new ShareEntry(null, worker, request, false, false, "unknown-work", data, -1);
			workResultSubmitter.getShareLogger().submitLogEntry(resultEntry);
			return buildGetWorkSubmitResponse((Integer) request.getId(), false, "unknown-work");
		}
		// work = entry.work;

		// Parse block
		PSJBlock block;
		try {
			block = new PSJBlock(Res.networkParameters(), data);
		} catch (ProtocolException e) {
			Stats.get().registerInvalidWorkSubmitted(request, worker, entry);
			ShareEntry resultEntry = new ShareEntry(entry.source, worker, request, false, false, "unparseable-work",
					data, entry.blockNum);

			workResultSubmitter.getShareLogger().submitLogEntry(resultEntry);
			return buildGetWorkSubmitResponse((Integer) request.getId(), false, "unparseable-work");
		}
		if (!block.checkTimestamp()) {
			Stats.get().registerInvalidWorkSubmitted(request, worker, entry);
			ShareEntry resultEntry = new ShareEntry(entry.source, worker, request, false, false,
					"invalid-future-timestamp", data, entry.blockNum);
			workResultSubmitter.getShareLogger().submitLogEntry(resultEntry);
			return buildGetWorkSubmitResponse((Integer) request.getId(), false, "invalid-future-timestamp");
		}
		try {
			// if (Res.isDebug()) {
			// Res.logInfo("Validating work against difficulty: " +
			// block.getDifficultyTargetAsInteger().toString(16));
			// // Res.logInfo("Our difficulty:                     " +
			// //
			// block.getDifficultyTargetAsInteger(Res.getCurrentDifficultyTarget()).toString(16));
			// Res.logInfo("Real difficulty:                    "
			// +
			// block.getDifficultyTargetAsInteger(Res.getRealDifficultyTarget()).toString(16));
			// Res.logInfo("Easy difficulty:                    "
			// +
			// block.getDifficultyTargetAsInteger(Res.getEasyDifficultyTarget()).toString(16));
			// Res.logInfo("Hash difficulty:                    " +
			// block.getHashAsString());
			// }

			// if (h.compareTo(target) > 0) {

			BigInteger hash = block.getHashAsInteger();

			if (!(hash.compareTo(Res.getEasyDifficultyTargetAsInteger()) > 0)) { // pass
																					// easy
																					// target

				ShareEntry resultEntry = new ShareEntry(entry.source, worker, request, true, false, null, data,
						entry.blockNum);
				worker.registerValidWork();

				// choose which queue:
				// if (!(hash.compareTo(Res.getRealDifficultyAsInteger()) > 0)
				// /*pass real target*/) { // manual check
				boolean passRealTarget = block.checkProofOfWork();
				if (passRealTarget /* pass real target */) { // block
																// should
																// contain
																// real
																// difficulty
																// to
																// check
																// against
					resultEntry.isRealSolution = true;
					TempLogger.logRealShare(new Date() + " - submit real solution to priority queue: " + data);
					workResultSubmitter.submitPriorityUpstream(resultEntry);
				} else {
					resultEntry.reportUpstream = isReportSuccessAgainstEasyTarget();
					if (!resultEntry.reportUpstream && Conf.get().isForceAllSubmitsUpstream()) {
						Res.logDebug("Forced work submission upstream: " + resultEntry.solution);
						resultEntry.reportUpstream = true;
					}
					workResultSubmitter.submitUpstream(resultEntry);
				}
				// everything is finally cool so build the response;
				// return buildGetWorkSubmitResponse((Integer) request.getId(),
				// isReportSuccessAgainstEasyTarget() ? true
				// : passRealTarget);
				return buildGetWorkSubmitResponse((Integer) request.getId(), resultEntry.ourResult, null);

			}
		} catch (VerificationException e) {
			throw new JsonRpcResponseException(-39, e.getMessage()); // should
																		// never
																		// happen
		}
		Stats.get().registerInvalidWorkSubmitted(request, worker, entry);
		ShareEntry resultEntry = new ShareEntry(entry.source, worker, request, false, false, "target-not-met", data,
				entry.blockNum);
		workResultSubmitter.getShareLogger().submitLogEntry(resultEntry);
		return buildGetWorkSubmitResponse((Integer) request.getId(), false, null);
	}

	protected boolean isReportSuccessAgainstEasyTarget() {
		// TODO optimize to static boolean.
		return nonDaemonSources.size() > 0;
	}

	private JsonRpcResponse buildGetWorkSubmitResponse(int requestID, boolean result, final String rejectReason) {
		JsonRpcResponse response = new JsonRpcResponse(requestID);
		response.setRejectReason(rejectReason);
		try {
			response.getJSONObject().putOpt("result", result);
			response.getJSONObject().put("error", JSONObject.NULL);
		} catch (JSONException e) {

		}
		return response;
	}

	public JsonRpcResponse handleRequest(JsonRpcRequest request, Worker worker) throws JsonRpcResponseException {

		if (shutdown) {
			JsonRpcResponse response = new JsonRpcResponse((Integer) request.getId());
			try {
				JSONObject error = new JSONObject();
				error.putOpt("code", -32098);
				error.putOpt("message", "Server shutdown in progress");
				response.setError(error);
				return response;
			} catch (JSONException e) {
				return response;
			}
		}

		String method = request.getMethod();
		if (method == null)
			throw new InvalidJsonRpcRequestException();
		if (JsonRpcRequest.METHOD_GETWORK.equals(method)) {
			if (isGetworkMethod(request.getMethod())) {
				JSONArray params = request.getParams();
				if (params != null && params.length() == 1) {
					return validateWork(request, worker);
				}
				workRequestLogger.submitLogEntry(request);
			}
			WorkSource source = chooseSourceForWork();
			if (Res.isDebug()) {
				int totalWork = Stats.get().getTotalWorkDelivered();
				double perc = totalWork == 0 ? 0 : (Stats.get().getSourceStats(source).getDeliveredWorkThisBlock()
						/ totalWork / source.getWeightingPerc());
				// Res.logInfo("Chose source: (" + leastUsedSource.getName() +
				// ") with usage: "
				// +
				// NumberFormat.getPercentInstance().format(lastUsedPercentage)
				// + " work requested/available: "
				// + numWorks + "/" + leastUsedSource.getWorksAvailable());
				// Res.logInfo("Chose source: " + source.getName() +
				// " - Utilization: " +
				// NumberFormat.getPercentInstance().format(perc));
			}
			Work work = source.getWork();
			JsonRpcResponse response = new JsonRpcResponse((Integer) request.getId());
			if (work == null) {
				try {
					JSONObject error = new JSONObject();
					error.putOpt("code", -31001);
					error.putOpt("message", "No work available");
					response.setError(error);
					return response;
				} catch (JSONException e) {
					return response;
				}

			}
			worker.registerWorkDelivered(1);
			response.setResult(work.getObject());
			mapWorkToSource(source, work);
			// if (Res.isDebug())
			// Res.logInfo("Target: " + work.getTarget());
			return response;
		} else {
			for (DaemonSource source : daemonSources) {
				if (source.isAllowJsonRpcPassThru() && source.getAllowedPassThruMethods().contains(method)) {
					return source.doProxyRequest(request);
				}
			}
			throw new MethodNotFoundException();
		}
	}

	private double totalWeighting = -1;

	/**
	 * calulates pool weightings into a percentage of total weightings. Called
	 * after configuring all sources.
	 */
	public void calcWeightings() {
		totalWeighting = 0;
		for (WorkSource source : daemonSources)
			totalWeighting += source.getWeighting();
		for (WorkSource source : nonDaemonSources)
			totalWeighting += source.getWeighting();
		for (WorkSource source : daemonSources)
			source.setWeightingPerc(source.getWeighting() / totalWeighting);
		for (WorkSource source : nonDaemonSources)
			source.setWeightingPerc(source.getWeighting() / totalWeighting);

		// populate allServingSources
		allServingSources.clear();
		for (WorkSource source : allSources) {
			if (source.getWeighting() > 0)
				allServingSources.add(source);
		}

	}

	ArrayDequeResourcePool<WorkSource[]> sourceArrayPool = new ArrayDequeResourcePool(5, 2);
	ArrayDequeResourcePool<HashMap<WorkSource, Double>> availabilityPool = new ArrayDequeResourcePool(50, 200);
	ArrayDequeResourcePool<MovingAverage<Double>> avgPool = new ArrayDequeResourcePool(50, 1);
	int chooses = 0;

	/**
	 * Updated algorithm for selecting worksource. First tries down the list for
	 * one that has enough work available. If none satisfy the start down the
	 * list again and take the first source that has more available than the
	 * average. Finally if still nothing work down the list and return the first
	 * source that is not offline.
	 * 
	 * @param numWorks
	 * @return
	 */
	public WorkSource chooseSourceForWork(int numWorks) {

		// if (Res.isDebug()) {
		// chooses++;
		// if (chooses % 1000 == 0) {
		// NumberFormat nf = NumberFormat.getPercentInstance();
		// Res.logInfo("sourceArrayPool hitrate:  " +
		// nf.format(sourceArrayPool.hitRate()));
		// Res.logInfo("availabilityPool hitrate: " +
		// nf.format(availabilityPool.hitRate()));
		// Res.logInfo("avgPool hitrate:          " +
		// nf.format(avgPool.hitRate()));
		// }
		// }

		// grab all out objects from recyclable pool. We'll reset them when we
		// get them
		WorkSource[] usages;
		boolean returnToPool = true;
		if (blockTracker.isAllSourcesOnCurrentBlock()) {
			usages = sourceArrayPool.getResource();
			if (usages == null) {
				usages = allServingSources.toArray(new WorkSource[allServingSources.size()]);
			}
		} else {
			usages = blockTracker.getSourcesOnCurrentBlock();
			returnToPool = false;
			if (Res.isTrace()) {
				Res.logTrace(Res.TRACE_BLOCKMON_WORKSOURCE, "Available work wources for request during block change: " + Arrays.toString(usages));
			}
		}
		if (usages.length == 1) {
			// no point going through the rest of these conniptions if there's
			// only one option.
			return usages[0];
		}

		Stats stats = Stats.get();
		double totalWork = stats.getTotalWorkDelivered();

		// ArrayList<WorkSource> usages = new ArrayList(allServingSources);
		// Collections.sort(usages, sourceUtilisationComparator);
		Arrays.sort(usages, sourceUtilisationComparator);

		// we can use the pooled resource regardless of length since it's a map.
		HashMap<WorkSource, Double> availability = availabilityPool.getResource();
		if (availability == null) {
			availability = new HashMap();
		}

		MovingAverage<Double> avg;
		if (returnToPool) {
			avg = avgPool.getResource();
			if (avg == null) {
				avg = new MovingAverage(usages.length);
			}
		} else {
			avg = new MovingAverage(usages.length);
		}

		// first check if we've got one that has enough works to service request
		// starting from least utilised.
		for (WorkSource source : usages) {
			SourceStats s = stats.getSourceStats(source);
			double perc = totalWork == 0 ? 0 : (s.getDeliveredWorkThisBlock() / totalWork) / source.getWeightingPerc();
			// Res.logDebug("Source usage: " + Stats.perc3Dec(perc));
			if (source.getWorksAvailable() >= numWorks) {
				// return resources to pool
				if (returnToPool) {
					sourceArrayPool.returnResource(usages);
					avg.reset();
					avgPool.returnResource(avg);
				}
				availability.clear();
				availabilityPool.returnResource(availability);

				return source;
			}
			double availableRatio = source.getWorksAvailable() / (double) numWorks;
			availability.put(source, availableRatio);
			avg.addValue(availableRatio);
		}
		// no source has enough work available to service entire request so
		// we'll take the first one
		// with more available than the average.
		double av = avg.getAvg();
		for (WorkSource source : usages) {
			double availableRatio = source.getWorksAvailable() / (double) numWorks;
			if (availableRatio >= av) {
				// return resources to pool
				if (returnToPool) {
					sourceArrayPool.returnResource(usages);
					avg.reset();
					avgPool.returnResource(avg);
				}
				availability.clear();
				availabilityPool.returnResource(availability);
				return source;
			}
		}
		// as a last resort we'll pick the first one with a failrate below 10%
		// or the one with the lowest failrate.
		WorkSource candidate = null;
		double lowestFailRate = 2;
		for (WorkSource source : usages) {
			MovingAverage failRateMa = stats.getSourceStats(source).getUpstreamRequestFailRateTiny();
			double failRate = failRateMa.getAvg();
			if (failRate != Double.NaN && failRate < 0.1d) { // NaN should only
																// occur when
																// the stats
																// have been
																// reset and no
																// new requests
																// have been
																// made
				// return resources to pool
				if (returnToPool) {
					sourceArrayPool.returnResource(usages);
					avg.reset();
					avgPool.returnResource(avg);
				}
				availability.clear();
				availabilityPool.returnResource(availability);
				return source;
			}
			if (failRate != Double.NaN && failRate < lowestFailRate) {
				lowestFailRate = failRate;
				candidate = source;
			}
		}
		// return resources to pool
		if (returnToPool) {
			sourceArrayPool.returnResource(usages);
			avg.reset();
			avgPool.returnResource(avg);
		}
		availability.clear();
		availabilityPool.returnResource(availability);

		if (candidate != null)
			return candidate;
		// should never get here but if still nothing then:
		return usages[0]; // if this throws a null pointer we were fucked
							// anyway.
	}

	/**
	 * Simple implementation, just grabs the source with least utilisation
	 * compared to it's weighting.
	 * 
	 * @param numWorks
	 * @return
	 */
	public WorkSource chooseSourceForWorkOld(int numWorks) {
		// TreeMap map = new TreeMap();
		Stats stats = Stats.get();
		WorkSource leastUsedSource = null;
		double leastUsedPercentage = 1;
		double lastUsedPercentage = 1;
		double totalWork = stats.getTotalWorkDelivered();

		for (WorkSource source : daemonSources) {
			SourceStats s = stats.getSourceStats(source);
			double perc = totalWork == 0 ? 0 : (s.getDeliveredWorkThisBlock() / totalWork) / source.getWeightingPerc();
			if (perc < leastUsedPercentage && (perc > 0 || s.getDeliveredWorkThisBlock() == 0)) {
				lastUsedPercentage = leastUsedPercentage;
				leastUsedPercentage = perc;
				leastUsedSource = source;
			} else if (leastUsedSource == null) {
				leastUsedSource = source;
			}
		}
		for (WorkSource source : nonDaemonSources) {
			SourceStats s = stats.getSourceStats(source);
			double perc = (s.getDeliveredWorkThisBlock() / totalWork) / source.getWeightingPerc();
			if (perc < leastUsedPercentage && (perc > 0 || s.getDeliveredWorkThisBlock() == 0)) {
				lastUsedPercentage = leastUsedPercentage;
				leastUsedPercentage = perc;
				leastUsedSource = source;
			} else if (leastUsedSource == null) {
				leastUsedSource = source;
			}
		}
		if (Res.isDebug()) {
			Res.logInfo("Chose source: (" + leastUsedSource.getName() + ") with usage: "
					+ NumberFormat.getPercentInstance().format(lastUsedPercentage) + " work requested/available: "
					+ numWorks + "/" + leastUsedSource.getWorksAvailable());
		}
		return leastUsedSource;
	}

	public WorkSource chooseSourceForWork() {
		return chooseSourceForWork(1);
	}

	public DaemonSource getDaemonSource() {
		return daemonSources.get(0);
	}

	// public ArrayList<DaemonSource> getLocalDaemonSources() {
	// return daemonSources;
	// }

	public boolean isGetworkMethod(String method) {
		return "getwork".equalsIgnoreCase(method);
	}

	//
	// public void setLocalDaemonSource(DaemonSource localSource) {
	// this.localSource = localSource;
	// }

	public ArrayList<DaemonSource> getDaemonSources() {
		return daemonSources;
	}

	public ArrayList<WorkSource> getAllSources() {
		return allSources;
	}

	public ArrayList<WorkSource> getNonDaemonSources() {
		return nonDaemonSources;
	}

	/**
	 * @param maxAgeToMapWorkSource
	 *            the maxAgeToMapWorkSource to set
	 */
	public void setMaxAgeToMapWorkSource(long maxAgeToMapWorkSource) {
		this.maxAgeToMapWorkSource = maxAgeToMapWorkSource;
	}

	/**
	 * @param useCompressedMapKeys
	 *            the useCompressedMapKeys to set
	 */
	public void setUseCompressedMapKeys(boolean useCompressedMapKeys) {
		this.useCompressedMapKeys = useCompressedMapKeys;
	}

	/**
	 * @param nonceRangePaddingFactor
	 *            the nonceRangePaddingFactor to set
	 */
	public void setNonceRangePaddingFactor(float nonceRangePaddingFactor) {
		this.nonceRangePaddingFactor = nonceRangePaddingFactor;
		enableNonceRange = nonceRangePaddingFactor > 0;
	}

	/**
	 * @param rollNTimeExpire
	 *            the rollNTimeExpire to set
	 */
	public void setRollNTimeExpire(long rollNTimeExpire) {
		this.rollNTimeExpire = rollNTimeExpire;
		enableRollNTime = rollNTimeExpire > 0;
	}

	/**
	 * @return the enableNonceRange
	 */
	public boolean isEnableNonceRange() {
		return enableNonceRange;
	}

	/**
	 * @return the enableRollNTime
	 */
	public boolean isEnableRollNTime() {
		return enableRollNTime;
	}

	/**
	 * @return the shareSubmitter
	 */
	public ShareSubmitter getWorkResultSubmitter() {
		return workResultSubmitter;
	}

	public long getCurrentBlock() {
		return blockTracker.getCurrentBlock();
	}

	/**
	 * @return the blockTracker
	 */
	public BlockChainTracker getBlockTracker() {
		return blockTracker;
	}

	/**
	 * @return the allowBreakWeightingRulesAfterBlockChange
	 */
	public boolean isAllowBreakWeightingRulesAfterBlockChange() {
		return allowBreakWeightingRulesAfterBlockChange;
	}

	/**
	 * @param allowBreakWeightingRulesAfterBlockChange
	 *            the allowBreakWeightingRulesAfterBlockChange to set
	 */
	public void setAllowBreakWeightingRulesAfterBlockChange(boolean allowBreakWeightingRulesAfterBlockChange) {
		this.allowBreakWeightingRulesAfterBlockChange = allowBreakWeightingRulesAfterBlockChange;
	}

	//
	// public void notifyBlockChange() {
	// if (acceptNotifyBlockChange) {
	// synchronized (this) {
	// acceptNotifyBlockChange = false;
	// for (WorkSource source : daemonSources)
	// source.notifyBlockChange();
	// for (WorkSource source : nonDaemonSources)
	// source.notifyBlockChange();
	// }
	// acceptNotifyBlockChange = true;
	// }
	// }

	public void flushCache() {
		// if (maxAgeToMapWorkSource > 0) {
		// synchronized (sentBlocksCache) {
		// cleanMap(sentBlocksCache);
		// synchronized (submittedWork) {
		// cleanMap(submittedWork);
		// }
		// }
		// if (lastBlocksentBlocksCache != null) {
		// synchronized (lastBlocksentBlocksCache) {
		// cleanMap(lastBlocksentBlocksCache);
		// cleanMap(lastBlockSubmittedWork);
		// }
		// }
		// }
		long now = System.currentTimeMillis();
		int trimCount = 0; // debug, remove
		int size = 0;
		int lastSize = 0;

		workSourceEntryRetensionProcedure.compareTime = System.currentTimeMillis() - maxAgeToMapWorkSource;
		if (maxAgeToMapWorkSource > 0) {
			synchronized (sentBlocksCache) {
				lastSize = sentBlocksCache.size();
				size += lastSize;
				sentBlocksCache.retainEntries(workSourceEntryRetensionProcedure);
				trimCount += lastSize - sentBlocksCache.size();
				synchronized (submittedWork) {
					lastSize = submittedWork.size();
					size += lastSize;
					submittedWork.retainEntries(workSourceEntryRetensionProcedure);
					trimCount += lastSize - submittedWork.size();
				}
			}
			if (lastBlocksentBlocksCache != null) {
				synchronized (lastBlocksentBlocksCache) {
					lastSize = lastBlocksentBlocksCache.size();
					size += lastSize;
					lastBlocksentBlocksCache.retainEntries(workSourceEntryRetensionProcedure);
					trimCount += lastSize - lastBlocksentBlocksCache.size();

					lastSize = lastBlockSubmittedWork.size();
					size += lastSize;
					lastBlockSubmittedWork.retainEntries(workSourceEntryRetensionProcedure);
					trimCount += lastSize - lastBlockSubmittedWork.size();
				}
			}
		}
		int duplicatesTrimmed = 0;
		for (WorkSource source : allSources) {
			duplicatesTrimmed += source.trimDuplicateMap();
		}
		if (Res.isDebug()) {
			// debug, remove
			Res.logTrace("Trimmed " + (trimCount) + " entries from workmap and " + duplicatesTrimmed
					+ " entries from duplicate check sets in " + (System.currentTimeMillis() - now) + "ms");
		}
	}

	/**
	 * @deprecated
	 * @param map
	 */
	private void cleanMap(Map<Object, WorkSourceEntry> map) {
		long now = System.currentTimeMillis();
		int trimCount = 0; // debug, remove
		int size = map.size();
		Iterator<Entry<Object, WorkSourceEntry>> i = map.entrySet().iterator();
		while (i.hasNext()) {
			if (now > i.next().getValue().createTime + maxAgeToMapWorkSource) {
				i.remove();
				trimCount++; // debug, remove
			}
		}
		if (Res.isDebug()) {
			// debug, remove
			Res.logInfo("Trimmed " + (trimCount) + " entries from workmap in " + (System.currentTimeMillis() - now)
					+ "ms");
		}
	}

	public int start() {
		int localStarted = 0;
		int upstreamStarted = 0;
		for (WorkSource source : nonDaemonSources) {
			Res.logInfo("Starting upstream work source proxy: " + source.getName());
			source.start();
			upstreamStarted++;
		}
		for (DaemonSource source : daemonSources) {
			Res.logInfo("Starting local work source proxy: " + source.getName());
			source.start();
			localStarted++;
		}
		return localStarted + upstreamStarted;
	}

	public void shutdown() {
		shutdown = true;
		for (WorkSource source : daemonSources)
			source.shutdown();
		for (WorkSource source : nonDaemonSources)
			source.shutdown();
		synchronized (this) {
			notifyAll();
		}
		synchronized (sentBlocksCache) {
			sentBlocksCache.notifyAll();
		}
		dumpWorkMap(Conf.get().getWorkMapFile());
	}

	public void dumpWorkMap(File file) {
		if (!Conf.get().isSafeRestart())
			return;
		Res.logInfo("Dumping workmap to file: " + file.getAbsolutePath());

		file.getParentFile().mkdirs();

		WorkSourceEntrySerializationContainer container = new WorkSourceEntrySerializationContainer();
		container.thisBlockNum = blockTracker.getCurrentBlock();
		container.lastBlockNum = blockTracker.getCurrentBlock() - 1;

		container.sentBlocksCache = sentBlocksCache;
		container.submittedWork = submittedWork;

		container.lastBlocksentBlocksCache = lastBlocksentBlocksCache;
		container.lastBlockSubmittedWork = lastBlockSubmittedWork;

		FileOutputStream fos = null;
		ObjectOutputStream out = null;
		try {
			fos = new FileOutputStream(file);
			out = new ObjectOutputStream(fos);
			out.writeObject(container);
			out.close();
		} catch (IOException ex) {
			ex.printStackTrace();
		}
	}

	public void restoreWorkMap(File file) {
		if (!Conf.get().isSafeRestart())
			return;
		if (Res.isDebug())
			Res.logInfo("Restoring workmap from file: " + file.getAbsolutePath());
		synchronized (sentBlocksCache) {
			if (file == null || !file.exists() || !file.canRead())
				return;
			FileInputStream fis = null;
			ObjectInputStream in = null;
			try {
				fis = new FileInputStream(file);
				in = new ObjectInputStream(fis);
				container = (WorkSourceEntrySerializationContainer) in.readObject();
				in.close();
			} catch (IOException ex) {
				Res.logError("Failed to restore workmap from file: " + file.getAbsolutePath()
						+ " this may be because the previous shutdown was interrupted.", ex);
				// fuck it, give it up, can't guarantee the map won't be
				// corrupted so don't try to use it.
				return;
			} catch (ClassNotFoundException ex) {
				ex.printStackTrace();
				return;
			}
			if (container != null) {
				long blockNum = blockTracker.getCurrentBlock();
				if (container.thisBlockNum == blockNum) {
					submittedWork = container.submittedWork;
					lastBlockSubmittedWork = container.lastBlockSubmittedWork;
					lastBlocksentBlocksCache = container.lastBlocksentBlocksCache;
					sentBlocksCache.putAll(container.sentBlocksCache);
				} else if (container.lastBlockNum == blockNum) {
					lastBlockSubmittedWork = container.submittedWork;
					lastBlocksentBlocksCache = container.sentBlocksCache;
					if (submittedWork == null)
						submittedWork = new THashMap();
					if (sentBlocksCache == null)
						sentBlocksCache = new THashMap();
				}
				container = null;
				file.delete();
			}
		}

	}

	public void join() {
		for (WorkSource source : daemonSources)
			source.join();
		for (WorkSource source : nonDaemonSources)
			source.join();
	}

	public void registerDaemonSource(DaemonSource source) {
		if (!daemonSources.contains(source))
			daemonSources.add(source);
		if (!allSources.contains(source))
			allSources.add(source);
		if (sourceByName.put(source.getName(), source) != null) {
			Res.logError("Cannot start server due to duplicate source name: " + source.getName());
			System.exit(1);
		}
		// usageMap.put(source, null);
		// availableSet.add(source);
	}

	public void registerNonDaemonSource(WorkSource source) {
		if (!nonDaemonSources.contains(source))
			nonDaemonSources.add(source);
		if (!allSources.contains(source))
			allSources.add(source);
		if (sourceByName.put(source.getName(), source) != null) {
			Res.logError("Cannot start server due to duplicate source name: " + source.getName());
			System.exit(1);
		}

		// usageMap.put(source, null);
		// availableSet.add(source);
	}

	public WorkSource getSourceByName(String sourceName) {
		return sourceByName.get(sourceName);
	}

	public int getWorkAvailable() {
		int available = 0;
		for (WorkSource source : blockTracker.getSourcesOnCurrentBlock()) {
			available += source.getWorksAvailable();
		}
		return available;
	}

}
