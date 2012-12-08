package com.shadworld.poolserver.conf;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.math.BigInteger;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;

import org.eclipse.jetty.util.thread.QueuedThreadPool;

import sun.misc.BASE64Decoder;

import com.google.bitcoin.core.NetworkParameters;
import com.google.bitcoin.core.Utils;
import com.shadworld.jsonrpc.JsonRpcClient;
import com.shadworld.utils.EndianTools;
import com.shadworld.utils.L;

public class Res {

	private static Res instance = new Res();
	private static DateFormat df = new SimpleDateFormat("kk:mm:ss.SSS");

	private static HashSet<String> allowedTraceTargets = new HashSet();

	public static final String TRACE_ALL = "all";
	public static final String TRACE_LP_SPAM = "lp_spam";
	public static final String TRACE_LONGPOLL = "longpoll";
	public static final String TRACE_LONGPOLL_TIMEOUT = "longpoll_timeout";
	public static final String TRACE_LONGPOLL_EMPTY_RESPONSE = "longpoll_empty";
	public static final String TRACE_BLOCKMON = "blockmon";
	public static final String TRACE_BLOCKMON_FIRE_CHECK = "blockmon_firecheck";
	public static final String TRACE_BLOCKMON_WORKSOURCE = "blockmon_worksource";
	public static final String TRACE_WORKER_CACHE = "workerCache";
	public static final String TRACE_WORKER_STATS = "workerStats";

	public static final String VTRACE_VERBOSE = "verbose";
	public static final String VTRACE_TERSE = "terse";

	static {
		allowedTraceTargets.add(TRACE_ALL);
		allowedTraceTargets.add(TRACE_LONGPOLL);
		allowedTraceTargets.add(TRACE_LP_SPAM);
		allowedTraceTargets.add(TRACE_LONGPOLL_TIMEOUT);
		allowedTraceTargets.add(TRACE_LONGPOLL_EMPTY_RESPONSE);
		allowedTraceTargets.add(TRACE_BLOCKMON);
		allowedTraceTargets.add(TRACE_BLOCKMON_FIRE_CHECK);
		allowedTraceTargets.add(TRACE_BLOCKMON_WORKSOURCE);
		allowedTraceTargets.add(TRACE_WORKER_CACHE);
		allowedTraceTargets.add(TRACE_WORKER_STATS);

	}

	private JsonRpcClient sharedClient;
	private JsonRpcClient sharedLongpollClient;

	private Object globalLock = new Object();
	private boolean debug = false;
	private boolean trace = false;
	private boolean traceTargetsAll = false;
	private HashSet<String> traceTargets = new HashSet();
	private boolean logStacktraces = false;

	private boolean sharesToStdout = false;
	private boolean requestsToStdout = false;

	// "0x00000000ffff0000000000000000000000000000000000000000000000000000"
	// "ffffffffffffffffffffffffffffffffffffffffffffffffffffffff00000000"
	// in big endian
	private String easyDifficultyTargetString = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffff00000000";
	private long easyDifficultyTarget = -1;
	private BigInteger easyDifficultyTargetAsInteger;

	// private long currentDifficulty = -1;
	// private BigInteger currentDifficultyAsInteger;

	private long realDifficultyTarget = -1;
	private BigInteger realDifficultyTargetAsInteger;

	NetworkParameters networkParams = NetworkParameters.prodNet();

	private boolean throttleWorkSubmits = false;
	private long throttleWorkSubmitTime = 80;

	private PrintWriter liveLogWriter;
	private Object liveLogWriterLock;

	// public static String getVersion() {
	// return "0.2.8";
	// }

	Res() {
		super();
		// swap from little endian to big endian for BigInteger parsing.
		easyDifficultyTargetAsInteger = new BigInteger(EndianTools.swapHexString(easyDifficultyTargetString, 1), 16);
		// setEasytDifficultyTargetInternal(0x1d00ffff);
	}
	
	public static void setLiveLogWriter(PrintWriter writer, Object liveLogLock) {
		if (instance.liveLogWriter != null)
			synchronized (instance.liveLogWriterLock) {
				instance.liveLogWriter.close();
				instance.liveLogWriterLock.notifyAll();
			}
		instance.liveLogWriter = writer;
		instance.liveLogWriterLock = liveLogLock;
	}

	public static void setDebug(boolean debug) {
		instance.debug = debug;
	}

	public static boolean isDebug() {
		return instance.debug;
	}

	public static void setTrace(boolean trace) {
		instance.trace = trace;
		if (trace)
			logInfo("Trace activated, available trace targets: " + allowedTraceTargets);
	}

	public static void addTraceTarget(String traceTarget) {
		if (!allowedTraceTargets.contains(traceTarget))
			logError("traceTarget: " + traceTarget + " not recognized.  Currently recognized trace targets: "
					+ allowedTraceTargets);
		instance.traceTargets.add(traceTarget);
		if (traceTarget.equalsIgnoreCase("all"))
			instance.traceTargetsAll = true;
	}

	public static boolean isTrace() {
		return instance.trace;
	}

	public static boolean isTrace(String... traceTargets) {
		return instance.trace
				&& (instance.traceTargetsAll || instance.traceTargets.containsAll(Arrays.asList(traceTargets)));
	}

	public static NetworkParameters networkParameters() {
		return instance.networkParams;
	}

	public static void setTestNet() {
		instance.networkParams = NetworkParameters.testNet();
	}

	public static void initSharedJsonRpcClients(int numSources) {
		instance.sharedClient = new JsonRpcClient(null, null, null);
		QueuedThreadPool pool = new QueuedThreadPool();
		pool.setDaemon(true);
		pool.setName("shared-httpclient");
		pool.setMinThreads(2);
		instance.sharedClient.getClient().setThreadPool(pool);

		instance.sharedLongpollClient = new JsonRpcClient(true, null, null, null);
		QueuedThreadPool lpool = new QueuedThreadPool();
		lpool.setDaemon(true);
		lpool.setName("shared-lp-httpclient");
		lpool.setMinThreads(numSources);
		instance.sharedLongpollClient.getClient().setThreadPool(lpool);
	}

	public static void stopSharedJsonRpcClients() {
		try {
			instance.sharedClient.getClient().stop();
		} catch (Exception e) {
			Res.logException(e);
		}
		try {
			instance.sharedLongpollClient.getClient().stop();
		} catch (Exception e) {
			Res.logException(e);
		}
	}

	public static JsonRpcClient getSharedClient() {
		return instance.sharedClient;
	}

	public static JsonRpcClient getLongpollSharedClient() {
		return instance.sharedLongpollClient;
	}

	private static String date() {
		return "[" + df.format(new Date()) + "] ";
	}

	public static void logInfo(String message) {
		logInfo(message, true);
	}

	public static void logInfo(String message, boolean date) {
		System.out.println((date ? date() : "") + message);
	}

	public static void logInfo(PrintWriter writer, String message) {
		logInfo(writer, message, true);
	}
	public static void logInfo(PrintWriter writer, String message, boolean liveLog) {
		System.out.println(date() + message);
		if (writer != null) {
			writer.println(date() + message);
			writer.flush();
		}
		if (liveLog)
			writeToLiveLog(message, "blue");
	}
	
	private static void writeToLiveLog(String message, String colour) {
		if (instance.liveLogWriter != null) {
			synchronized (instance.liveLogWriter) {
				try {
					message = "<font color=\'" + colour + "\'>" + message + "</font><br/>\n";
					instance.liveLogWriter.write(date() + message);
					instance.liveLogWriter.flush();
				} catch (Exception e) {
					try {
						instance.liveLogWriter.close();
					} catch (Exception e1) {
					}
					instance.liveLogWriter = null;
					synchronized (instance.liveLogWriterLock) {
						instance.liveLogWriterLock.notifyAll();
					}
					
				}
			}
		}
	}

	public static void logWarn(String message) {
		logError(date() + "[WARN] " + message, null, false);
	}

	public static void logWarn(String message, Throwable e) {
		logError(date() + "[WARN] " + message, e, false);
	}

	public static void logDebug(String message) {
		if (instance.debug) {
			logInfo(message);
			writeToLiveLog(message, "black");
		}
	}

	public static void logTrace(String message) {
		if (instance.trace) {
			logInfo(date() + "TRACE[all] " + message, false);
			writeToLiveLog("TRACE[all] " + message, "purple");
		}
	}

	public static void logTrace(String traceTarget, String message) {
		if (isTrace(traceTarget)) {
			logInfo(date() + "TRACE[" + traceTarget + "] " + message, false);
			writeToLiveLog("TRACE[" + traceTarget + "] " + message, "purple");
		}
	}

	public static void logTraceGroups(String message, String... traceTargets) {
		if (isTrace(traceTargets)) {
			logInfo(date() + "TRACE" + Arrays.toString(traceTargets) + " " + message, false);
			writeToLiveLog("TRACE" + Arrays.toString(traceTargets) + " " + message, "purple");
		}
	}

	public static void logException(Throwable e) {
		if (instance.logStacktraces) {
			e.printStackTrace();
			if (instance.liveLogWriter != null) {
				try {
					instance.liveLogWriter.write("<font color='red'><pre>");
					e.printStackTrace(instance.liveLogWriter);
					instance.liveLogWriter.write("</pre></font><br/>");
				} catch (Exception e1) {
				}
			}
		} else {
			System.err.println(date() + e.getMessage());
			writeToLiveLog(e.getMessage(), "red");
		}
	}

	public static void logError(String message) {
		logError(message, null, true);
	}

	public static void logError(String message, Throwable e) {
		logError(message, e, true);
	}

	public static void logError(String message, Throwable e, boolean date) {
		System.err.println((date ? date() : "") + message);
		writeToLiveLog(message, "red");
		if (e != null && instance.logStacktraces)
			logException(e);
	}

	public static Object getGlobalLock() {
		return instance.globalLock;
	}

	// public static long getCurrentDifficultyTarget() {
	// return instance.currentDifficulty;
	// }
	//
	// public static BigInteger getCurrentDifficultyTargetAsInteger() {
	// return instance.currentDifficultyAsInteger;
	// }
	//
	// public static void setCurrentDifficultyTarget(long compactDifficulty) {
	// instance.currentDifficulty = compactDifficulty;
	// instance.currentDifficultyAsInteger =
	// Utils.decodeCompactBits(compactDifficulty);
	// }
	//
	public static long getRealDifficultyTarget() {
		return instance.realDifficultyTarget;
	}

	public static BigInteger getRealDifficultyTargetAsInteger() {
		return instance.realDifficultyTargetAsInteger;
	}

	public static void setRealDifficultyTarget(long compactDifficulty) {
		instance.realDifficultyTarget = compactDifficulty;
		instance.realDifficultyTargetAsInteger = Utils.decodeCompactBits(compactDifficulty);
	}

	public static String getEasyDifficultyTargetAsString() {
		return instance.easyDifficultyTargetString;
	}

	public static long getEasyDifficultyTarget() {
		return instance.easyDifficultyTarget;
	}

	public static BigInteger getEasyDifficultyTargetAsInteger() {
		return instance.easyDifficultyTargetAsInteger;
	}

	private void setEasytDifficultyTargetInternal(long compactDifficulty) {
		easyDifficultyTarget = compactDifficulty;
		easyDifficultyTargetAsInteger = Utils.decodeCompactBits(compactDifficulty);
		easyDifficultyTargetString = easyDifficultyTargetAsInteger.toString(16);
		if (easyDifficultyTargetAsInteger.compareTo(networkParams.proofOfWorkLimit) > 0)
			networkParams.proofOfWorkLimit = easyDifficultyTargetAsInteger;
	}

	public static void setEasytDifficultyTarget(long compactDifficulty) {
		instance.setEasytDifficultyTargetInternal(compactDifficulty);
	}

	/**
	 * decode Basic Auth string
	 * 
	 * @param auth
	 * @return array of length 2 with username and password or null if something
	 *         went wrong.
	 */
	public static String[] decodeAuthString(String auth) {
		if (auth == null || auth.length() < 8)
			return null;
		String encodedNamePassword = auth.substring(6);
		BASE64Decoder dec = new BASE64Decoder();
		String unencodedNamePassword;
		try {
			unencodedNamePassword = new String(dec.decodeBuffer(encodedNamePassword));
		} catch (IOException e) {
			return null;
		}
		String[] parts = unencodedNamePassword.split(":");
		if (parts == null || parts.length != 2)
			return null;
		return parts;
	}

	public static void setLogStacktraces(boolean logStacktraces) {
		instance.logStacktraces = logStacktraces;
	}

	public static void setLogSharesToStdout(boolean sharesToStdout) {
		instance.sharesToStdout = sharesToStdout;
	}

	public static boolean isLogSharesToStdout() {
		return instance.sharesToStdout;
	}

	public static void setLogRequestsToStdout(boolean requestsToStdout) {
		instance.requestsToStdout = requestsToStdout;
	}

	public static boolean isLogRequestsToStdout() {
		return instance.requestsToStdout;
	}

	public static void throttleWorkSubmits(boolean throttle) {
		instance.throttleWorkSubmits = throttle;
		Res.logDebug("Submit Throttling on: " + throttle);
	}

	public static void throttleWorkSubmitsHarder(float loadFactor) {
		float raiseFactor = 1 + loadFactor;
		if (raiseFactor < 2)
			raiseFactor = 2;
		if (instance.throttleWorkSubmitTime < 2)
			instance.throttleWorkSubmitTime = (long) raiseFactor;
		else
			instance.throttleWorkSubmitTime = (long) (instance.throttleWorkSubmitTime * raiseFactor + 1);
		// sanity check
		if (instance.throttleWorkSubmitTime > 2000)
			instance.throttleWorkSubmitTime = 2000;
		Res.logDebug("Raising submit throttle to " + instance.throttleWorkSubmitTime + "ms");
	}

	public static void throttleWorkSubmitsSofter() {
		instance.throttleWorkSubmitTime = instance.throttleWorkSubmitTime / 2;
		if (instance.throttleWorkSubmitTime < 2)
			instance.throttleWorkSubmitTime = 2;
		Res.logDebug("Dropping submit throttle to " + instance.throttleWorkSubmitTime + "ms");
	}

	public static long throttleWorkSubmitTime() {
		return instance.throttleWorkSubmitTime;
	}

	public static boolean isThrottleWorkSubmits() {
		return instance.throttleWorkSubmits;
	}

}
