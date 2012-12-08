package com.shadworld.poolserver.stats;

import java.text.NumberFormat;

import sun.reflect.ReflectionFactory.GetReflectionFactoryAction;

import com.shadworld.jsonrpc.JsonRpcRequest;
import com.shadworld.jsonrpc.JsonRpcResponse;
import com.shadworld.poolserver.PSJExchange;
import com.shadworld.poolserver.WorkSourceEntry;
import com.shadworld.poolserver.conf.Res;
import com.shadworld.poolserver.entity.Worker;
import com.shadworld.poolserver.source.WorkEntry;
import com.shadworld.poolserver.source.WorkSource;
import com.shadworld.util.MovingAverage;
import com.sun.org.apache.bcel.internal.generic.GETSTATIC;

public class SourceStats {
	
	Stats stats;
	WorkSource source;
	
	MovingAverage expiredWorkRate = new MovingAverage(Stats.MA_PERIOD_MED);
	MovingAverage delayedServicedRequestRate = new MovingAverage(Stats.MA_PERIOD_MED);
	MovingAverage immediatelyServicedRequestRate = new MovingAverage(Stats.MA_PERIOD_MED);
	MovingAverage notServicedRequestRate = new MovingAverage(Stats.MA_PERIOD_MED);
	MovingAverage multiGetDeliveryRate = new MovingAverage(Stats.MA_PERIOD_MED);
	int deliveredWorkThisBlock = 0;
	
	MovingAverage duplicateWorkRate = new MovingAverage(Stats.MA_PERIOD_MED);
	
	MovingAverage httpRequestFailRate = new MovingAverage(Stats.MA_PERIOD_MED);
	
	MovingAverage httpTripTimeSuccess = new MovingAverage(Stats.MA_PERIOD_MED);
	MovingAverage httpTripTimeHeaderComplete = new MovingAverage(Stats.MA_PERIOD_MED);
	MovingAverage httpTripTimeFail = new MovingAverage(Stats.MA_PERIOD_MED);
	MovingAverage httpTripTimeExpire = new MovingAverage(Stats.MA_PERIOD_MED);
	
	MovingAverage workCacheBufferCapacity = new MovingAverage(Stats.MA_PERIOD_MED);
	MovingAverage cacheGrowthRate = new MovingAverage(Stats.MA_PERIOD_MED);
	MovingAverage cacheGrowthRateShort = new MovingAverage(Stats.MA_PERIOD_SHORT);
	
	int httpRequestsIssued = 0;

	int workReceived = 0;
	
	int workSubmitted = 0;
	int invalidWorkSubmitted = 0;
	int unknownWorkSubmitted = 0;
	int validRealWorkSubmitted = 0;
	int validWorkSubmitted = 0;
	

	public SourceStats(Stats stats, WorkSource source) {
		super();
		this.stats = stats;
		this.source = source;
	}

	/**
	 * Level 1 stat - use for internal optimization
	 * @param isDuplicate 
	 */
	public void registerWorkDuplicate(WorkEntry entry, boolean isDuplicate) {
		duplicateWorkRate.addValue(isDuplicate ? 1 : 0);
	}

	/**
	 * Level 1 stat - use for internal optimization
	 */
	public void registerWorkExpired(WorkEntry entry, boolean expired) {
		expiredWorkRate.addValue(expired ? 1 : 0);
	}

	/**
	 * Level 1 stat - use for internal optimization
	 */
	public void registerWorkHttpRequest(WorkSource source) {
		httpRequestsIssued++;
	}

	/**
	 * Level 1 stat - use for internal optimization
	 */
	public void registerWorkReceived(WorkEntry entry) {
		workReceived++;
	}

	/**
	 * Level 1 stat - use for internal optimization
	 */
	public void registerWorkNotAvailableImmediately(WorkSource source, int numRequested, int numAvailable) {
		immediatelyServicedRequestRate.addValue(0);
		
		Double last = null;
		if (workCacheBufferCapacity.getMarker() > 0) {
			last = workCacheBufferCapacity.getAvg();
		}
		workCacheBufferCapacity.addValue(numRequested - numAvailable);
		if (last != null) {
			double diff = workCacheBufferCapacity.getAvg() - last;
			cacheGrowthRate.addValue(diff);
			cacheGrowthRateShort.addValue(diff);
		}
		if (numRequested > 1) {
			multiGetDeliveryRate.addValue(0);
		}
	}

	/**
	 * Level 0 stat - use for internal optimization
	 */
	public void registerWorkAvailableImmediately(WorkEntry entry, int numRequested, int numDelivered, int spareWorkAfterRequestServiced) {
		for (int i = 0; i < numDelivered; i++)
			expiredWorkRate.addValue(0);
		deliveredWorkThisBlock += numDelivered;
		immediatelyServicedRequestRate.addValue(1);
		notServicedRequestRate.addValue(0);
		Double last = null;
		if (workCacheBufferCapacity.getMarker() > 0) {
			last = workCacheBufferCapacity.getAvg();
		}
		workCacheBufferCapacity.addValue(spareWorkAfterRequestServiced);
		if (last != null) {
			double diff = workCacheBufferCapacity.getAvg() - last;
			cacheGrowthRate.addValue(diff);
			cacheGrowthRateShort.addValue(diff);
		}
		if (numRequested > 1) {
			multiGetDeliveryRate.addValue((double) numDelivered / numRequested);
		}
	}

	/**
	 * Level 0 stat - use for internal optimization
	 */
	public void registerWorkAvailableAfterWait(WorkSource source, int numRequested, int numDelivered, int spareWorkAfterRequestServiced) {
		for (int i = 0; i < numDelivered; i++)
			expiredWorkRate.addValue(0);
		deliveredWorkThisBlock += numDelivered;
		delayedServicedRequestRate.addValue(1);
		notServicedRequestRate.addValue(0);
		if (numRequested > 1) {
			multiGetDeliveryRate.addValue((double) numRequested / numDelivered);
		}
		if (numRequested > 1) {
			multiGetDeliveryRate.addValue((double) numDelivered / numRequested);
		}
	}

	/**
	 * Level 0 stat - use for internal optimization
	 */
	public void registerWorkNotAvailableAfterTimeout(WorkSource source, int numRequested) {
		delayedServicedRequestRate.addValue(0);
		notServicedRequestRate.addValue(1);
		if (numRequested > 1) {
			multiGetDeliveryRate.addValue(0);
		}
	}

	/**
	 * Level 1 stat - use for internal optimization
	 */
	public void registerHttpReponseComplete(PSJExchange ex, long triptime) {
		httpRequestFailRate.addValue(0);
		httpTripTimeSuccess.addValue(triptime);
	}
	
	/**
	 * Level 2 stat - use for performance tuning/troubleshooting
	 */
	public void registerHttpReponseHeaderComplete(PSJExchange ex, long triptime) {
		httpTripTimeHeaderComplete.addValue(triptime);
	}
	
	/**
	 * Level 2 stat - use for performance tuning/troubleshooting
	 */
	public void registerHttpRetry(PSJExchange ex, long triptime) {
		// TODO Auto-generated method stub
		
	}

	/**
	 * Level 1 stat - use for internal optimization 
	 */
	public void registerHttpConnectionFail(PSJExchange ex, Throwable x, long triptime) {
		httpRequestFailRate.addValue(1);
		httpTripTimeFail.addValue(triptime);
	}

	
	/**
	 * Level 2 stat - use for performance tuning/troubleshooting
	 */
	public void registerHttpException(PSJExchange ex, Throwable x, long triptime) {
		httpRequestFailRate.addValue(1);
		httpTripTimeFail.addValue(triptime);
	}

	
	/**
	 * Level 2 stat - use for performance tuning/troubleshooting
	 */
	public void registerHttpExpire(PSJExchange ex, long triptime) {
		httpRequestFailRate.addValue(1);
		httpTripTimeExpire.addValue(triptime);
	}

	/**
	 * Level 2 stat - use for efficiency metrics
	 */
	public void registerInvalidWorkSubmitted(JsonRpcRequest request, Worker worker, WorkSourceEntry entry) {
		workSubmitted++;
		invalidWorkSubmitted++;
	}
	
	/**
	 * Level 2 stat - use for efficiency metrics
	 */
	public void registerUnknownWorkSubmitted(JsonRpcRequest request, Worker worker) {
		workSubmitted++;
		unknownWorkSubmitted++;
	}
	
	/**
	 * Level 2 stat - use for efficiency metrics
	 */
	public void registerValidRealWorkSubmitted(JsonRpcRequest request, Worker worker, WorkSourceEntry entry) {
		workSubmitted++;
		validWorkSubmitted++;
		validRealWorkSubmitted++;
	}
	
	/**
	 * Level 2 stat - use for efficiency metrics
	 */
	public void registerValidWorkSubmitted(JsonRpcRequest request, Worker worker, WorkSourceEntry entry) {
		workSubmitted++;
		validWorkSubmitted++;
	}
	
	MovingAverage upstreamRequestFailRate = new MovingAverage(Stats.MA_PERIOD_SHORT);
	MovingAverage upstreamRequestFailRateTiny = new MovingAverage(Stats.MA_PERIOD_TINY);
	
	public void registerUpstreamRequest(WorkSource workSource) {
		// TODO Auto-generated method stub
		
	}

	public void registerUpstreamRequestFailed(WorkSource workSource) {
		upstreamRequestFailRate.addValue(1);
		upstreamRequestFailRateTiny.addValue(1);
	}

	public void registerUpstreamRequestSuccess(WorkSource workSource) {
		upstreamRequestFailRate.addValue(0);
		upstreamRequestFailRateTiny.addValue(0);
	}

	int workMappedToSource = 0;
	int workSuppliedButExpiredBeforeReturn = 0;
	
	/**
	 * Level 2 stat - use for efficiency metrics
	 */
	public void registerSuppliedWorkExpiredFromCacheBeforeReturn(WorkSourceEntry entry) {
		workSuppliedButExpiredBeforeReturn++;
	}
	
	/**
	 * Level 2 stat - use for efficiency metrics
	 */
	public void registerWorkMappedToSource(WorkSourceEntry entry) {
		workMappedToSource++;
	}
	
	/**
	 * 
	 * 
	 *  GETTERS
	 * 
	 * 
	 */
	
	
	
	/**
	 * Received JSON-RPC error from upstream server after getwork request
	 * @param response
	 */
	public void registerWorkReceivedError(String errorMessage) {
		Res.logDebug("JsonRpcError: [" + source.getName() + "] " + errorMessage);
	}

	/**
	 * MA rate of cached work that expired before delivery to downstream getworkClient.  To high a value suggests cache is too large.
	 * @return the expiredWorkRate
	 */
	public double getExpiredWorkRate() {
		return expiredWorkRate.getAvg();
	}

	/**
	 * MA rate of requests serviced after cache was emtpy and waiting for new work to come in from upstream.
	 * @return the delayedServicedRequestRate
	 */
	public double getDelayedServicedRequestRate() {
		return delayedServicedRequestRate.getAvg();
	}

	/**
	 * MA rate of requests serviced from over capacity cache
	 * @return the immediatelyServicedRequestRate
	 */
	public double getImmediatelyServicedRequestRate() {
		return immediatelyServicedRequestRate.getAvg();
	}
	
	
	
	/**
	 * @return the multiGetDeliveryRate
	 */
	public double getMultiGetDeliveryRate() {
		return multiGetDeliveryRate.getAvg();
	}

	/**
	 * @return the deliveredWorkThisBlock
	 */
	public int getDeliveredWorkThisBlock() {
		return deliveredWorkThisBlock;
	}

	/**
	 * MA rate of duplicate works to total works from upstream source
	 * @return the duplicateWorkRate
	 */
	public double getDuplicateWorkRate() {
		return duplicateWorkRate.getAvg();
	}

	/**
	 * MA rate of failed HTTP requests
	 * @return the httpRequestFailRate
	 */
	public double getHttpRequestFailRate() {
		return httpRequestFailRate.getAvg();
	}

	/**
	 * avg trip time for completed HTTP requests
	 * @return the httpTripTimeSuccess
	 */
	public double getHttpTripTimeSuccess() {
		return httpTripTimeSuccess.getAvg();
	}

	/**
	 * avg trip time for return of HTTP headers
	 * @return the httpTripTimeHeaderComplete
	 */
	public double getHttpTripTimeHeaderComplete() {
		return httpTripTimeHeaderComplete.getAvg();
	}

	/**
	 * avg trip time for failed HTTP requests, includes connection fail and exceptions
	 * @return the httpTripTimeFail
	 */
	public double getHttpTripTimeFail() {
		return httpTripTimeFail.getAvg();
	}

	/**
	 * avg trip time for expired HTTP requests
	 * @return the httpTripTimeExpire
	 */
	public double getHttpTripTimeExpire() {
		return httpTripTimeExpire.getAvg();
	}

	/**
	 * med MA of cache growth rate.  Rate is last spare capacity MA - current spare capacity MA
	 * @return the cacheGrowthRate
	 */
	public double getCacheGrowthRate() {
		return cacheGrowthRate.getAvg();
	}

	/**
	 * short MA of cache growth rate.  Rate is last spare capacity MA - current spare capacity MA
	 * @return the cacheGrowthRateShort
	 */
	public double getCacheGrowthRateShort() {
		return cacheGrowthRateShort.getAvg();
	}
	
	/**
	 * rate of requests that failed to be serviced due to no work available even after waiting for new work to come in/
	 * @return the notServicedRequestRate
	 */
	public double getNotServicedRequestRate() {
		return notServicedRequestRate.getAvg();
	}
	
	/**
	 * This value represents the excess work available in the WorkSource's cache after servicing a getwork or getworks request.
	 * @return the workCacheBufferCapacity
	 */
	public MovingAverage getWorkCacheBufferCapacity() {
		return workCacheBufferCapacity;
	}

	/**
	 * total http requests made by WorkSource since stats reset.
	 * @return the httpRequestsIssued
	 */
	public int getHttpRequestsIssued() {
		return httpRequestsIssued;
	}


	/**
	 * Number of works received for submission to cache, this includes potential duplicates.
	 * @return the workReceived
	 */
	public int getWorkReceived() {
		return workReceived;
	}

	/**
	 * number of works submitted by downstream clients since last stats reset
	 * @return the workSubmitted
	 */
	public int getWorkSubmitted() {
		return workSubmitted;
	}

	/**
	 * number of invalid works submitted by downstream clients since last stats reset
	 * @return the invalidWorkSubmitted
	 */
	public int getInvalidWorkSubmitted() {
		return invalidWorkSubmitted;
	}

	/**
	 * number of works submitted by downstream clients since last stats reset
	 * @return the unknownWorkSubmitted
	 */
	public int getUnknownWorkSubmitted() {
		return unknownWorkSubmitted;
	}

	/**
	 * number of works submitted that meet the real difficult target, by downstream clients since last stats reset
	 * @return the validRealWorkSubmitted
	 */
	public int getValidRealWorkSubmitted() {
		return validRealWorkSubmitted;
	}

	/**
	 * number of works submitted that meet the real easy target, by downstream clients since last stats reset
	 * @return the validWorkSubmitted
	 */
	public int getValidWorkSubmitted() {
		return validWorkSubmitted;
	}
	
	/**
	 * @return the upstreamRequestFailRate
	 */
	public MovingAverage getUpstreamRequestFailRate() {
		return upstreamRequestFailRate;
	}

	/**
	 * @return the upstreamRequestFailRateTiny
	 */
	public MovingAverage getUpstreamRequestFailRateTiny() {
		return upstreamRequestFailRateTiny;
	}

	/**
	 * number of works mapped to this source for duplicate checks
	 * @return the workMappedToSource
	 */
	public int getWorkMappedToSource() {
		return workMappedToSource;
	}

	/**
	 * number of works that were supplied to a downstream getworkClient but never submitted for validation. 
	 * @return the workSuppliedButExpiredBeforeReturn
	 */
	public int getWorkSuppliedButExpiredBeforeReturn() {
		return workSuppliedButExpiredBeforeReturn;
	}

	
	/**
	 * 
	 * OUTPUT STRINGS
	 * 
	 * 
	 */
	
	public String toString() {
		return toString(0);
	}
	
	public String toString(int indent) {
		StringBuilder sb = new StringBuilder();
		Stats.indent(sb, indent);
		sb.append("Stats for source: [").append(source.getName()).append("] \n");
		indent++;
		indent++;
		Stats.indent(sb, indent);
		sb.append("Current Block: ").append(source.getMyCurrentBlock()).append("\n");
		
		sb.append(toCacheStatsString(indent));
		sb.append(toWorkSubmitStatsString(indent));
		sb.append(toHttpStatsString(indent));
		Stats.indent(sb, indent);
		sb.append("Cache Age: \n");
		Stats.indent(sb, indent + 1);
		sb.append(source.getCacheStats()).append("\n");
		return sb.toString();
	}
	
	public String toCacheStatsString(int indent) {
		StringBuilder sb = new StringBuilder();
		Stats.indent(sb, indent);
		sb.append("Cache: \n");
		indent++;
		Stats.indent(sb, indent);
		sb.append("Work Received: ").append(getWorkReceived()).append("\n");
		Stats.indent(sb, indent);
		sb.append("Work Delivered: ").append(getDeliveredWorkThisBlock()).append("\n");
		Stats.indent(sb, indent);		
		sb.append("Upstream Request Fail Rate: ").append(Stats.perc3Dec(getUpstreamRequestFailRate().getAvg())).append("\n");
		Stats.indent(sb, indent);
		sb.append("Upstream Request Fail Rate Tiny: ").append(Stats.perc3Dec(getUpstreamRequestFailRateTiny().getAvg())).append("\n");
		Stats.indent(sb, indent);
		sb.append("Immediately Serviced Rate: ").append(Stats.perc3Dec(getImmediatelyServicedRequestRate())).append("\n");
		Stats.indent(sb, indent);
		sb.append("MultiGet Delivery Rate: ").append(Stats.perc3Dec(getMultiGetDeliveryRate())).append("\n");
		Stats.indent(sb, indent);
		sb.append("Delayed Serviced Rate: ").append(Stats.perc3Dec(getDelayedServicedRequestRate())).append("\n");
		Stats.indent(sb, indent);
		sb.append("Not Serviced Rate: ").append(Stats.perc3Dec(getNotServicedRequestRate())).append("\n");
		Stats.indent(sb, indent);
		sb.append("Expired Work Rate: ").append(Stats.perc3Dec(getExpiredWorkRate())).append("\n");
		Stats.indent(sb, indent);
		sb.append("Duplicate Work Rate: ").append(Stats.perc3Dec(getDuplicateWorkRate())).append("\n");
		Stats.indent(sb, indent);
		sb.append("Cache Growth Rate: ").append(Stats.perc3Dec(getCacheGrowthRate())).append("\n");
		Stats.indent(sb, indent);
		sb.append("Cache Growth Rate Short: ").append(Stats.perc3Dec(getCacheGrowthRateShort())).append("\n");
		return sb.toString();
	}
	
	public String toHttpStatsString(int indent) {
		StringBuilder sb = new StringBuilder();
		Stats.indent(sb, indent);
		sb.append("HTTP: \n");
		indent++;
		Stats.indent(sb, indent);
		sb.append("Requests Issued: ").append(getHttpRequestsIssued()).append("\n");
		Stats.indent(sb, indent);
		sb.append("Fail Rate: ").append(Stats.perc3Dec(getHttpRequestFailRate())).append("\n");
		Stats.indent(sb, indent);
		sb.append("Success trip time: ").append(Stats.format2Dec(getHttpTripTimeSuccess())).append(" ms\n");
		Stats.indent(sb, indent);
		sb.append("Header trip time: ").append(Stats.format2Dec(getHttpTripTimeHeaderComplete())).append(" ms\n");
		Stats.indent(sb, indent);
		sb.append("Fail trip time: ").append(Stats.format2Dec(getHttpTripTimeFail())).append(" ms\n");
		Stats.indent(sb, indent);
		sb.append("Expire trip time: ").append(Stats.format2Dec(getHttpTripTimeExpire())).append(" ms\n");
		return sb.toString();
	}
	
	public String toWorkSubmitStatsString(int indent) {
		StringBuilder sb = new StringBuilder();
		Stats.indent(sb, indent);
		sb.append("Work Submissions: \n");
		indent++;
		Stats.indent(sb, indent);
		sb.append("Work Supplied: ").append(getWorkMappedToSource()).append("\n");
		Stats.indent(sb, indent);
		sb.append("Work Submitted: ").append(getWorkSubmitted()).append("\n");
		Stats.indent(sb, indent);
		sb.append("Work Submitted Invalid: ").append(getInvalidWorkSubmitted()).append("\n");
		Stats.indent(sb, indent);
		sb.append("Work Submitted Unknown: ").append(getUnknownWorkSubmitted()).append("\n");
		Stats.indent(sb, indent);
		sb.append("Work Submitted Valid: ").append(getValidWorkSubmitted()).append("\n");
		Stats.indent(sb, indent);
		sb.append("Work Submitted Valid Real: ").append(getValidRealWorkSubmitted()).append("\n");
		return sb.toString();
	}
	
	
	
}
