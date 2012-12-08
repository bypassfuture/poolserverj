package com.shadworld.poolserver.source;

import com.shadworld.jsonrpc.JsonRpcClient;
import com.shadworld.poolserver.conf.Conf;
import com.shadworld.poolserver.conf.Res;
import com.shadworld.poolserver.stats.Stats;
import com.shadworld.util.MovingAverage;
import com.shadworld.util.RateCalculator;
import com.shadworld.util.Time;

public class SourceState {

	WorkSource source;

	public SourceState(WorkSource source) {
		super();
		this.source = source;
	}

	int consecutiveConnectFails = 0;
	long lastConnectFailReset = 0;
	int consecutiveHttpFails = 0;
	long lastHttpFailReset = 0;
	int consecutiveHttpAuthFails = 0;
	int consecutiveHttpBusyFails = 0;
	
	private static final int RATE_CALC_SIZE = 5000;

	MovingAverage cacheExcess = new MovingAverage(20, true);
	MovingAverage cacheExcessTrend = new MovingAverage(7);
	MovingAverage cacheRetreivalAge = new MovingAverage(20, true);
	MovingAverage cacheRetreivalAgeTrend = new MovingAverage(7);

	RateCalculator incomingRate = new RateCalculator(RATE_CALC_SIZE, 5, 5, true);
	MovingAverage incomingFullfillmentRate = new MovingAverage(20);
	long totalIncoming = 0;
	RateCalculator outgoingRequestedRate = new RateCalculator(RATE_CALC_SIZE, 5, 5, true);
	RateCalculator outgoingDeliveredRate = new RateCalculator(RATE_CALC_SIZE, 5, 5, true);
	long totalOutgoingRequested = 0;
	long totalOutgoingDelivered = 0;

	int upstreamRequestsSinceBlockChange = 0;

	long lastAdjustment = System.currentTimeMillis();
	int requestsSinceLastAdjustment = 15;

	/**
	 * hint about whether upstream source is likely to be offline, currently if
	 * three connect fails in a row.
	 * 
	 * @return
	 */
	public boolean isProbablyOffline() {
		return consecutiveConnectFails > 3;
	}

	/**
	 * hint about whether upstream source is likely not authorizing connections,
	 * currently if more 401 or 403's in a row than maxConcurrent connections.
	 * 
	 * @return
	 */
	public boolean isProbablyBadAuth() {
		return consecutiveHttpAuthFails > source.maxConcurrentDownloadRequests;
	}

	public boolean isProbablyOverloaded() {
		return consecutiveHttpBusyFails > 3 || consecutiveConnectFails > 3;
	}

	public void adjust() {
		long now = System.currentTimeMillis();
//		if (now > lastAdjustment + 60000) {
//			requestsSinceLastAdjustment = 0;
//			lastAdjustment = System.currentTimeMillis();
//			synchronized (source.swapClientLock) {
//				JsonRpcClient oldClient = source.client;
//				source.initClient();
//				try {
//					oldClient.getClient().stop();
//				} catch (Exception e) {
//				}
//			}
//		}
		if (requestsSinceLastAdjustment < 20 || lastAdjustment > now - 1000)
			return;
		if (isProbablyBadAuth() || isProbablyOffline() || isProbablyOverloaded())
			return;

		// need to find the optimum mix between cache size and askrate. We only
		// reduce askrate in exceptional cirumstances
		// because it is already limited by max cache size. Askrate reduced when
		// it appears to cause a slow down in
		// http trip time
		// We raise it if we are getting work requests faster than
		// we can service them.
		//
		//adjustCacheSize();
		//adjustAskRate();
	}

	private void adjustAskRate() {
		long now = System.currentTimeMillis();
//		if (requestsSinceLastAdjustment < 20 || lastAdjustment > now - 1000)
//			return;
		double serviceRatio = outgoingDeliveredRate.avgRate(1) / outgoingRequestedRate.avgRate(1);

		// double askRate = 1000 / ((source.currentIntervalBetweenHttpRequests +
		// triptime) * source.concurrentDownloadRequests *
		// source.currentAskRate);
		if (serviceRatio < 0.99d) {
			// not enough in cache to service requests
			// calculate shortfall in askrate:
			int currentAskRate = source.currentAskRate;
			int newRate = (int) (currentAskRate * (1 / serviceRatio));
			if (newRate == currentAskRate)
				newRate++;
			source.setCurrentAskRate(newRate);
			if (source.currentAskRate <= newRate) { // hit limit so we need
													// another strategy.
				if (now > lastConnectFailReset + 20000) {
					double triptime = Stats.get().getSourceStats(source).getHttpTripTimeHeaderComplete();
					long oldInterval = source.currentIntervalBetweenHttpRequests;
					double triptimeWithDelay = triptime + oldInterval;
					double triptimeWithMinDelay = triptime + source.minIntervalBetweenHttpRequests;
					// calc potential benefit of changing delay between requests
					double benefit = (triptimeWithDelay - triptimeWithMinDelay) / triptimeWithDelay;
					if (benefit >= (1 - serviceRatio)) { // benefit approaches
															// what's needed
						// calc backwards to see how much it actually needs to
						// be reduced.
						double excess = (benefit - 1 + serviceRatio) * 1.1; // add
																			// a
																			// margin
						long newInterval = (long) (oldInterval * (1 - excess));
						source.setIntervalBetweenHttpRequests(newInterval);
						if (source.currentIntervalBetweenHttpRequests != oldInterval) {
							if (Res.isDebug()) {
								Res.logInfo("[" + source.getName() + "] reduced minIntervalBetweenHttpRequests by "
										+ (oldInterval - newInterval) + " to "
										+ source.currentIntervalBetweenHttpRequests);
							}
						} else {
							// neither strategy worked so try to increase
							// concurrent requests.
							int concurrentRequests = source.concurrentDownloadRequests;
							source.setConcurrentDownloadRequests((int) (concurrentRequests / serviceRatio));
							if (source.concurrentDownloadRequests != concurrentRequests) {
								if (Res.isDebug()) {
									Res.logInfo("[" + source.getName() + "] increase concurrentDownloadRequests by "
											+ (source.concurrentDownloadRequests - concurrentRequests) + " to "
											+ source.concurrentDownloadRequests);
								}
							}
						}
					}
				}
			} else {
				if (Res.isDebug()) {
					Res.logInfo("[" + source.getName() + "] increased Askrate by " + (newRate - currentAskRate)
							+ " to " + newRate);
				}
			}

		} else {
			double incomingSpeed = incomingRate.avgRate(1);
			double incomingRatio = incomingSpeed / outgoingDeliveredRate.avgRate(1);
			//double varianceFactor = incomingRate.rateStandardDeviation(1) 
			if (incomingRatio > 1.20d) {
				// too many incoming, likely that work is timing out in cache so
				// adjust ask rate down.
				int askRate = source.currentAskRate;
				double adjustment = ((incomingRatio - 1) / 2) + 1; // half the
																	// excess
																	// should
																	// prevent
																	// us
																	// falling
																	// under
																	// capacity.
				int newRate = (int) (askRate / adjustment);
				source.setCurrentAskRate(newRate);
				if (source.currentAskRate == askRate) {
					// hasn't changed so try another strategy - simple increase
					// delay between requests by 10%
					long oldDelay = source.currentIntervalBetweenHttpRequests;
					long newDelay = (long) (oldDelay * 1.1f);
					if (newDelay == oldDelay)
						newDelay++;
					source.setIntervalBetweenHttpRequests(newDelay);
					if (source.currentIntervalBetweenHttpRequests != oldDelay) {
						if (Res.isDebug()) {
							Res.logInfo("[" + source.getName() + "] increased minIntervalBetweenHttpRequests by "
									+ (oldDelay - newDelay) + " to " + source.currentIntervalBetweenHttpRequests);
						}
					}
				}
			}
		}
	}

	private void adjustCacheSize() {
		long now = System.currentTimeMillis();
//		if (requestsSinceLastAdjustment < 20 || lastAdjustment > now - 1000)
//			return;
		if ((upstreamRequestsSinceBlockChange > 20 || source.lastBlockChange < now - 3000)
				&& cacheExcess.getMarker() > 1) {

//			requestsSinceLastAdjustment = 0;
//			lastAdjustment = now;

			double excess = cacheExcess.getAvg();
			double excessRatio = excess / (source.currentCacheSize + 1);
			double trend = cacheExcessTrend.getAvg();
			double age = cacheRetreivalAge.getAvg();
			double halfMaxAge = source.maxWorkAgeToFlush / 2;
			if (excessRatio > 0.3d /*&& trend > 0 */&& age > halfMaxAge) {
				int reduction = (int) (excess / 2);
				setCacheSize(source.currentCacheSize - reduction);
				if (Res.isDebug()) {
					Res.logInfo("[" + source.getName() + "] Cache size reduced by " + reduction + " to " + source.currentCacheSize
							+ ".  Avg Excess: " + Stats.format2Dec(excess) + " Excess Ratio: " + Stats.format2Dec(excessRatio) + " Trend: " + Stats.format2Dec(trend));
				}
				return;
			}
			if (excess < 0.2d /*&& trend < 0*/) {
				int increase = (int) (-excess + 1);
				if (increase == 0)
					increase++;
				setCacheSize(source.currentCacheSize + increase);
				if (Res.isDebug()) {
					Res.logInfo("[" + source.getName() + "] Cache size increased by " + increase + " to " + source.currentCacheSize
							+ ".  Avg Excess: " + Stats.format2Dec(excess) + " Excess Ratio: " + Stats.format2Dec(excessRatio) + " Trend: " + Stats.format2Dec(trend));
				}
				return;
			}
//			if (Res.isDebug()) {
//				Res.logInfo("[" + source.getName() + "] Cache adjustment not required.  Avg Excess: " + Stats.format2Dec(excess) + " Excess Ratio: " + Stats.format2Dec(excessRatio) + " Trend: "
//						+ Stats.format2Dec(trend));
//			}
		} else {

		}
	}

	private void setCacheSize(int newSize) {
		if (newSize > source.maxCacheSize)
			source.currentCacheSize = source.maxCacheSize;
		else if (newSize < source.minCacheSize)
			source.currentCacheSize = source.minCacheSize;
		else
			source.currentCacheSize = newSize;
	}

	public void registerHttpSuccess() {
		upstreamRequestsSinceBlockChange++;
		consecutiveConnectFails = 0;
		consecutiveHttpFails = 0;
		lastConnectFailReset = System.currentTimeMillis();
		lastHttpFailReset = lastConnectFailReset;
		consecutiveHttpBusyFails = 0;
		consecutiveHttpAuthFails = 0;
	}

	void registerHttpFail(int httpStatusCode) {
		upstreamRequestsSinceBlockChange++;
		consecutiveHttpFails++;
		consecutiveConnectFails = 0;
		lastConnectFailReset = System.currentTimeMillis();
		if (httpStatusCode / 100 == 5)
			consecutiveHttpBusyFails++;
		if (httpStatusCode == 403 || httpStatusCode == 401)
			consecutiveHttpAuthFails++;

	}

	public void registerConnectFail() {
		upstreamRequestsSinceBlockChange++;
		consecutiveConnectFails++;
	}

	void registerValidCacheRetrieval(int excessEntries, long cacheEntryTime) {
		double last = cacheExcess.getAvg();
		cacheExcess.addValue(excessEntries);
		if (!Double.isNaN(last) && !Double.isInfinite(last)) {
			double current = cacheExcess.getAvg();
			if (!Double.isNaN(current) && !Double.isInfinite(current))
				cacheExcessTrend.addValue(current - last);
		}
		last = cacheRetreivalAge.getAvg();
		cacheRetreivalAge.addValue(System.currentTimeMillis() - cacheEntryTime);
		if (!Double.isNaN(last)) {
			double current = cacheRetreivalAge.getAvg();
			if (!Double.isNaN(current))
				cacheRetreivalAge.addValue(current - last);
		}
	}

	void registerIncomingWork(int numWorks, int numRequested) {
		totalIncoming += numWorks;
		incomingRate.registerEvents(numWorks);
		incomingFullfillmentRate.addValue(numWorks / (double) numRequested);
	}

	void registerWorkRequest(int numRequested, int numDelivered) {
		totalOutgoingDelivered += numDelivered;
		totalOutgoingRequested += numRequested;
		outgoingRequestedRate.registerEvents(numRequested);
		outgoingDeliveredRate.registerEvents(numDelivered);
		requestsSinceLastAdjustment += numRequested;
	}

	public String toString() {
		StringBuilder sb = new StringBuilder("State [").append(source.name).append("]\n");
		try {
			Stats.indent(sb, 2);
			sb.append("Current Cache Max: ").append(source.currentCacheSize).append(" - min/max: ").append(source.minCacheSize).append("/" ).append(source.maxCacheSize).append("\n");
			Stats.indent(sb, 2);
			sb.append("Current Cache Size: ").append(source.cache.size()).append("\n");
			Stats.indent(sb, 2);
			sb.append("Concurrent DL Requests: ").append(source.concurrentDownloadRequests).append(" - min/max: ").append(source.minConcurrentDownloadRequests).append("/" ).append(source.maxConcurrentDownloadRequests).append("\n");
			Stats.indent(sb, 2);
			sb.append("DL Request Interval (ms): ").append(source.currentIntervalBetweenHttpRequests).append(" - min/max: ").append(source.minIntervalBetweenHttpRequests).append("/" ).append(source.maxIntervalBetweenHttpRequests).append("\n");
			Stats.indent(sb, 2);
			sb.append("Current Ask Rate (works/req): ").append(source.currentAskRate).append("\n");
			
			Stats.indent(sb, 2);
			sb.append("Consecutive Connect Fails: ").append(consecutiveConnectFails).append("\n");
			Stats.indent(sb, 2);
			sb.append("Consecutive Http Fails: ").append(consecutiveHttpFails).append("\n");
			Stats.indent(sb, 2);
			sb.append("Consecutive Http Auth Fails: ").append(consecutiveHttpAuthFails).append("\n");
			Stats.indent(sb, 2);
			sb.append("Consecutive Http Busy Fails: ").append(consecutiveHttpBusyFails).append("\n");
			Stats.indent(sb, 2);
			sb.append("Cache Excess: ").append(Stats.format2Dec(cacheExcess.getAvg())).append("\n");
			Stats.indent(sb, 2);
			sb.append("Cache Excess Trend: ").append(Stats.format2Dec(cacheExcessTrend.getAvg())).append("\n");
			Stats.indent(sb, 2);
			sb.append("Cache Retreival Age: ").append((long) cacheRetreivalAge.getAvg()).append("\n");
			Stats.indent(sb, 2);
			sb.append("Incoming Rate: ").append(Stats.format2Dec(incomingRate.currentRate(Time.SEC))).append("/sec\n");
			Stats.indent(sb, 2);
			sb.append("Incoming Fullfillment Rate: ").append(Stats.perc2Dec(incomingFullfillmentRate.getAvg()))
					.append("\n");
			Stats.indent(sb, 2);
			sb.append("Outgoing Requested Rate: ");
			if (totalOutgoingRequested > 0)
				sb.append(Stats.format2Dec(outgoingRequestedRate.currentRate(Time.SEC))).append("/sec");
			sb.append("\n");
			Stats.indent(sb, 2);
			sb.append("Outgoing Delivered Rate: ");
			if (totalOutgoingRequested > 0)
				sb.append(Stats.format2Dec(outgoingDeliveredRate.currentRate(Time.SEC))).append("/sec");
			sb.append("\n");
			Stats.indent(sb, 2);
			sb.append("Outgoing Fullfillment Rate: ");
			if (totalOutgoingRequested > 0)
				sb.append(Stats.perc2Dec(outgoingDeliveredRate.currentRate(Time.SEC)
							/ outgoingRequestedRate.currentRate(Time.SEC))).append("");
			sb.append("\n");
			
		} catch (Exception e) {
			sb.append("\n");
		}
		return sb.toString();
	}
}
