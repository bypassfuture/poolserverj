package com.shadworld.poolserver.stats;

import java.text.NumberFormat;
import java.util.HashMap;

import com.shadworld.jsonrpc.JsonRpcRequest;
import com.shadworld.jsonrpc.JsonRpcResponse;
import com.shadworld.poolserver.PSJExchange;
import com.shadworld.poolserver.PoolServer;
import com.shadworld.poolserver.WorkSourceEntry;
import com.shadworld.poolserver.conf.Conf;
import com.shadworld.poolserver.conf.Res;
import com.shadworld.poolserver.entity.Work;
import com.shadworld.poolserver.entity.Worker;
import com.shadworld.poolserver.source.WorkEntry;
import com.shadworld.poolserver.source.WorkSource;

public class Stats {
	
	static final int MA_PERIOD_TINY = 30;
	static final int MA_PERIOD_SHORT = 100;
	static final int MA_PERIOD_MED = 500;
	static final int MA_PERIOD_LONG = 2000;
	

	private static final int MAX_STATS = 3;
	private static final int MIN_STATS = 0;
	
	private static final Stats s = new Stats();
	private static final NullStats nullStats = new NullStats();
	
	private Stats currentStats = this;
	private PoolServer server;
	private boolean statsCompleteFullBlock = false;
	
	private HashMap<WorkSource, SourceStats> sourcesStats = new HashMap();
	private WorkerCacheStats workerStats = new WorkerCacheStats();
	private WorkProxyStats proxyStats = new WorkProxyStats();
	private WorkSubmitStats workSubmitStats = new WorkSubmitStats();
	
	private int totalWorkDelivered = 0;
	private int workDeliveredAfterWait = 0;
	
	public static void setPoolServer(PoolServer server) {
		s.server = server;
	}
	
	public static boolean setStatsLevel(int level) {
		if (level > MAX_STATS || level < MIN_STATS) {
			return false;
		}
		/**
		 * This looks back to front because we are using substractive inheritance.  Each level extends the one
		 * above and overrides a few extra methods with empty methods.
		 */
		if (level == 0)
			s.currentStats = nullStats;
		else if (level == 1)
			s.currentStats = new StatsLevel3();
		else if (level == 2)
			s.currentStats = new StatsLevel2();
		else if (level == 3)
			s.currentStats = new StatsLevel1();
		return true;
	}
	
	public static Stats get() {
		return s.currentStats;
	}
	
	private static NumberFormat nf2Dec;
	private static NumberFormat nf3Dec;

	static {
		nf2Dec = NumberFormat.getInstance();
		nf2Dec.setMaximumFractionDigits(2);
		nf3Dec = NumberFormat.getInstance();
		nf3Dec.setMaximumFractionDigits(3);
	}
	
	public static String perc2Dec(Number n) {
		return nf2Dec.format(n.doubleValue() * 100) + "%";
	}
	
	public static String perc3Dec(Number n) {
		return nf3Dec.format(n.doubleValue() * 100) + "%";
	}
	
	public static String format2Dec(Number n) {
		return nf2Dec.format(n.doubleValue());
	}
	
	public static String format3Dec(Number n) {
		return nf3Dec.format(n.doubleValue());
	}
	
	public static void indent(StringBuilder sb, int indent) {
		for (int i = 0; i < indent; i++)
			sb.append("  ");
	}
	
	public SourceStats getSourceStats(WorkSource source) {
		SourceStats stats = sourcesStats.get(source);
		if (stats == null) {
			stats = new SourceStats(this, source);
			sourcesStats.put(source, stats);
		}
		return stats;
	}

	/**
	 * Level 2 stat - use for performance tuning
	 */
	public void registerWorkerCacheHit(Worker worker) {
		workerStats.cacheHits++;
		workerStats.cacheHitRate.addValue(1);
	}

	/**
	 * Level 2 stat - use for performance tuning
	 */
	public void registerWorkerCacheMiss(Worker worker) {
		workerStats.cacheMisses++;
		workerStats.cacheHitRate.addValue(0);
	}

	/**
	 * Level 3 stat - shouldn't be left on for long periods because it doesn't
	 * flush the map.
	 * @param username
	 */
	public void registerWorkerNotFound(String username) {
		Integer num = workerStats.notFoundWorkers.put(username, 1);
		if (num != null)
			workerStats.notFoundWorkers.put(username, ++num);
	}

	/**
	 * Level 3 stat - shouldn't be left on for long periods because it doesn't
	 * flush the map.
	 * @param username
	 */
	public void registerWorkerDataBaseFail(String username, Exception e) {
		workerStats.workerDatabaseFails.put(username, e.getMessage());
	}

	/**
	 * Level 1 stat - use for internal optimization
	 * @param isDuplicate 
	 */
	public void registerWorkDuplicate(WorkSource source, WorkEntry entry, boolean isDuplicate) {
		getSourceStats(source).registerWorkDuplicate(entry, isDuplicate);
	}

	/**
	 * Level 1 stat - use for internal optimization
	 */
	public void registerWorkExpired(WorkSource source, WorkEntry entry, boolean expired) {
		getSourceStats(source).registerWorkExpired(entry, expired);
	}
	
	/**
	 * Level 1 stat - use for internal optimization
	 */
	public void registerWorkHttpRequest(WorkSource source) {
		getSourceStats(source).registerWorkHttpRequest(source);
	}

	/**
	 * Level 1 stat - use for internal optimization
	 */
	public void registerWorkReceived(WorkSource source, WorkEntry entry) {
		getSourceStats(source).registerWorkReceived(entry);
	}

	/**
	 * Level 1 stat - use for internal optimization
	 */
	public void registerWorkNotAvailableImmediately(WorkSource source, int numRequested, int numAvailable) {
		getSourceStats(source).registerWorkNotAvailableImmediately(source, numAvailable, numAvailable);
	}

	/**
	 * Level 0 stat - use for internal optimization
	 */
	public void registerWorkAvailableImmediately(WorkSource source, WorkEntry entry, int numRequested, int numDelivered, int spareWorkAfterRequestServiced) {
		totalWorkDelivered += numDelivered;
		getSourceStats(source).registerWorkAvailableImmediately(entry, numRequested, numDelivered, spareWorkAfterRequestServiced);
	}

	/**
	 * Level 0 stat - use for internal optimization
	 */
	public void registerWorkAvailableAfterWait(WorkSource source, int numRequested, int numDelivered, int spareWorkAfterRequestServiced) {
		totalWorkDelivered += numDelivered;
		workDeliveredAfterWait += numDelivered;
		getSourceStats(source).registerWorkAvailableAfterWait(source, numRequested, numDelivered, spareWorkAfterRequestServiced);
	}

	/**
	 * Level 0 stat - use for internal optimization
	 */
	public void registerWorkNotAvailableAfterTimeout(WorkSource source, int numRequested) {
		getSourceStats(source).registerWorkNotAvailableAfterTimeout(source, numRequested);
	}

	public void registerWorkReceivedError(WorkSource source, String errorMessage) {
		getSourceStats(source).registerWorkReceivedError(errorMessage);
	}
	
	/**
	 * Level 1 stat - use for internal optimization
	 */
	public void registerHttpReponseComplete(PSJExchange ex, long triptime) {
		if (ex.workSourceOwner != null)
			getSourceStats(ex.workSourceOwner).registerHttpReponseComplete(ex, triptime);
		else if (ex.submitterOwner != null)
			workSubmitStats.registerHttpReponseComplete(ex, triptime);
	}
	
	/**
	 * Level 2 stat - use for performance tuning/troubleshooting
	 */
	public void registerHttpReponseHeaderComplete(PSJExchange ex, long triptime) {
		if (ex.workSourceOwner != null)
			getSourceStats(ex.workSourceOwner).registerHttpReponseHeaderComplete(ex, triptime);
		else if (ex.submitterOwner != null)
			workSubmitStats.registerHttpReponseHeaderComplete(ex, triptime);
	}
	
	/**
	 * Level 2 stat - use for performance tuning/troubleshooting
	 */
	public void registerHttpRetry(PSJExchange ex, long triptime) {
		if (ex.workSourceOwner != null)
			getSourceStats(ex.workSourceOwner).registerHttpRetry(ex, triptime);
		else if (ex.submitterOwner != null)
			workSubmitStats.registerHttpRetry(ex, triptime);
	}

	/**
	 * Level 1 stat - use for internal optimization 
	 */
	public void registerHttpConnectionFail(PSJExchange ex, Throwable x, long triptime) {
		if (ex.workSourceOwner != null)
			getSourceStats(ex.workSourceOwner).registerHttpConnectionFail(ex, x, triptime);
		else if (ex.submitterOwner != null)
			workSubmitStats.registerHttpConnectionFail(ex, x, triptime);
	}

	
	/**
	 * Level 2 stat - use for performance tuning/troubleshooting
	 */
	public void registerHttpException(PSJExchange ex, Throwable x, long triptime) {
		if (ex.workSourceOwner != null)
			getSourceStats(ex.workSourceOwner).registerHttpException(ex, x, triptime);
		else if (ex.submitterOwner != null)
			workSubmitStats.registerHttpException(ex, x, triptime);
	}

	
	/**
	 * Level 2 stat - use for performance tuning/troubleshooting
	 */
	public void registerHttpExpire(PSJExchange ex, long triptime) {
		if (ex.workSourceOwner != null)
			getSourceStats(ex.workSourceOwner).registerHttpExpire(ex, triptime);
		else if (ex.submitterOwner != null)
			workSubmitStats.registerHttpExpire(ex, triptime);
	}

	
	/**
	 * Level 2 stat - use for efficiency metrics
	 */
	public void registerInvalidWorkSubmitted(JsonRpcRequest request, Worker worker, WorkSourceEntry entry) {
		// TODO Auto-generated method stub
		
	}
	
	/**
	 * Level 2 stat - use for efficiency metrics
	 */
	public void registerUnknownWorkSubmitted(JsonRpcRequest request, Worker worker) {
		// TODO Auto-generated method stub
		
	}
	
	/**
	 * Level 2 stat - use for efficiency metrics
	 */
	public void registerValidRealWorkSubmitted(JsonRpcRequest request, Worker worker, WorkSourceEntry entry) {
		// TODO Auto-generated method stub
		
	}
	
	/**
	 * Level 2 stat - use for efficiency metrics
	 */
	public void registerValidWorkSubmitted(JsonRpcRequest request, Worker worker, WorkSourceEntry entry) {
		// TODO Auto-generated method stub
		
	}

	/**
	 * Level 2 stat - use for efficiency metrics
	 */
	public void registerSuppliedWorkExpiredFromCacheBeforeReturn(WorkSourceEntry entry) {
		getSourceStats(entry.getSource()).registerSuppliedWorkExpiredFromCacheBeforeReturn(entry);
	}
	
	public void registerWorkMappedToSource(WorkSourceEntry entry) {
		getSourceStats(entry.getSource()).registerWorkMappedToSource(entry);
	}
	
	public void registerUpstreamRequest(WorkSource workSource) {
		getSourceStats(workSource).registerUpstreamRequest(workSource);
	}

	public void registerUpstreamRequestFailed(WorkSource workSource) {
		getSourceStats(workSource).registerUpstreamRequestFailed(workSource);
	}

	public void registerUpstreamRequestSuccess(WorkSource workSource) {
		getSourceStats(workSource).registerUpstreamRequestSuccess(workSource);
	}
	
	
	
	/**
	 * @return the totalWorkDelivered
	 */
	public int getTotalWorkDelivered() {
		return totalWorkDelivered;
	}

	/**
	 * @return the workDeliveredAfterWait
	 */
	public int getWorkDeliveredAfterWait() {
		return workDeliveredAfterWait;
	}

	public String getWorkProxyStats() {
		StringBuilder sb = new StringBuilder();
		sb.append("Local Sources: ").append("\n");
		for (WorkSource source: server.getWorkProxy().getDaemonSources()) {
			sb.append("\t").append(source.getName()).append("\n");
			sb.append("\t").append(source.getCacheStats()).append("\n");
			sb.append("\t").append(source.getDuplicateCalcString()).append("\n");
		}
		sb.append("\n").append("Remote Sources").append("\n");
		for (WorkSource source: server.getWorkProxy().getNonDaemonSources()) {
			sb.append("\t").append(source.getName()).append("\n");
			sb.append("\t").append(source.getCacheStats()).append("\n");
			sb.append("\t").append(source.getDuplicateCalcString()).append("\n");
		}
		return sb.toString();
	}
	
	public String getSourceStatsString() {
		StringBuilder sb = new StringBuilder();
		sb.append("Longpoll Connections: ").append(server.getBlockTracker().getNumLongpollConnections()).append(" / ").append(Conf.get().getHttpLPMaxConnections());
		sb.append("\nWorkSource Stats: \n");
		Stats.indent(sb, 1);
		for (SourceStats source: sourcesStats.values()) {
			sb.append(source.toString(2)).append("\n");
		}
		return sb.toString();
	}

}
