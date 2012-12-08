package com.shadworld.poolserver.conf;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;

import com.google.bitcoin.core.PSJBlock;
import com.shadworld.poolserver.PoolServer;
import com.shadworld.poolserver.WorkerProxy;
import com.shadworld.poolserver.db.shares.DefaultPreparedStatementSharesDBFlushEngine;
import com.shadworld.poolserver.db.worker.WorkerDBFetchEngine;
import com.shadworld.poolserver.logging.ShareLogger;
import com.shadworld.poolserver.notify.HttpGetNotifyBlockChangeMethod;
import com.shadworld.poolserver.notify.NotifyBlockChangeMethod;
import com.shadworld.poolserver.servlet.auth.WorkerAuthenticator;
import com.shadworld.poolserver.source.DaemonSource;
import com.shadworld.poolserver.source.WorkSource;
import com.shadworld.signal.SignalInterceptor;
import com.shadworld.signal.SignalLogger;
import com.shadworld.sql.MySql;
import com.shadworld.sql.PostgreSql;
import com.shadworld.sql.Sql;
import com.shadworld.sql.Sqlite3;
import com.shadworld.util.Time;
import com.shadworld.utils.Convert;
import com.shadworld.utils.FileUtil;
import com.shadworld.utils.StringTools;

import sun.reflect.generics.reflectiveObjects.NotImplementedException;

public class Conf {

	private static Conf conf;

	public static final String DEFAULT_PROPERTIES_FILE = "PoolServerJ.properties";

	Properties properties;

	private File homeDir;
	private File workMapFile;

	protected PoolServer poolServer;
	private WorkerAuthenticator workerAuthenticator;

	private boolean forceAllSubmitsUpstream = false;

	// private Properties props;

	private String usernameColumn = "username";
	private String userPasswordColumn = "password";
	private String userIdColumn = "id";
	private String userAllowedHostsColumn = "allowed_hosts";
	private String selectUserQuery;
	private String selectUserListQuery;
	private String insertShareQuery;
	private Sql workerSql;
	private Sql sharesSql;

	private int httpJsonRpcPort;
	private String httpJsonRpcPath;

	private int httpLPJsonRpcPort;
	private String httpLPJsonRpcUrl;
	private String httpLPJsonRpcPath;
	private int httpLPTimeout;
	private int httpLPMaxConnections;

	private boolean enableLongpoll = true;
	private int longpollMaxConnectionsPerWorker = -1;
	private int nativeLongpollPort = 8950;
	private int nativeLongpollTimeout = 2000;


	private boolean enableQoS = false;
	private int QoSMaxRequestsToServiceConcurrently = 10;
	private boolean enableDoSFilter = false;
	private int DoSFilterMaxRequestsPerSecondBeforeThrottle = 4;

	private boolean safeRestart = true;

	private boolean logJettyStats = false;
	
	private long flushCacheInterval = 5000;
	private long workerCacheExpiry = Time.HR;
	private boolean useFixedTimeWorkerCacheExpiry = false;

	private boolean enableManagentInterface = false;
	private boolean allowShutdownByManagementInterface = false;
	private int managementInterfacePort = 8331;
	private boolean bindManagementInterfaceOnlyToLocahost;
	final private HashSet<String> allowedManagementAddresses = new HashSet();

	// extensions and memory management
	private String rollNTimeString;


	
	public static Conf get() {
		return conf;
	}

	public void setPoolServer(PoolServer server) {
		poolServer = server;
	}

	public static void init(PoolServer poolServer, File propsFile) {
		init(poolServer, propsFile, true);
	}

	public static void init(PoolServer poolServer, File propsFile, boolean doUpdate) {
		try {
			Constructor<? extends Conf> con = poolServer.ConfClass().getConstructor(PoolServer.class, File.class);
			conf = con.newInstance(poolServer, propsFile);
		} catch (Exception e) {
			Res.logError("Failed to build Config");
			e.printStackTrace();
			System.exit(1);
		}
		// conf = new Conf(poolServer, propsFile);
		if (doUpdate)
			conf.update(propsFile);
	}

	public Conf(PoolServer poolServer, File propsFile) {
		this.poolServer = poolServer;
	}

	private void update(File propsFile) {

		String path = getClass().getProtectionDomain().getCodeSource().getLocation().getPath();
		Res.logInfo("user.dir: " + System.getProperty("user.dir"));
		// Res.logInfo("homeDir: " + System.getProperty("homeDir"));
		Res.logInfo("Home path set to: " + path);
		String bin = path.substring(path.lastIndexOf("/"));
		if (path != null && !path.isEmpty()) {
			homeDir = new File(System.getProperty("user.dir", "."), bin);
		}
		if (homeDir == null || !homeDir.exists()) {
			Res.logError("failed to set home directory: " + homeDir.getAbsolutePath());
			Res.logError("PoolServerJ must be started from the directory that contains poolserverj.jar");
			System.exit(1);
		} else {
			if (homeDir.isFile())
				homeDir = homeDir.getParentFile();
			if (homeDir != null && homeDir.exists())
				homeDir = homeDir.getParentFile();
			if (homeDir == null || !homeDir.exists()) {
				Res.logError("failed to set home directory: " + path);
				System.exit(1);
			}
			Res.logInfo("Home directory set from jar file location to: " + homeDir.getAbsolutePath());
		}

		if (propsFile == null) {
			// File propsFile = null;
			path = System.getProperty("config", null);
			if (path == null) {
				propsFile = new File(homeDir, DEFAULT_PROPERTIES_FILE);
				if (!propsFile.exists()) {
					Res.logError("Could not find properties file.  Either create file with default name ("
							+ DEFAULT_PROPERTIES_FILE
							+ ") in same directory as runtime jar or specific on command line with option -Dconfig=mypropertiesfilepath"
							+ "\nCurrently resolving to: " + propsFile.getAbsolutePath());
					System.exit(1);
				}
			} else if (path != null) {
				if (path.startsWith("/") || path.startsWith("\\")) {
					propsFile = new File(path);
				} else {
					propsFile = new File(homeDir, path);
				}
				if (!propsFile.exists()) {
					Res.logError("Could not find properties file.  Either create file with default name ("
							+ DEFAULT_PROPERTIES_FILE
							+ ") in same directory as runtime jar or specific on command line with option -Dconfig=mypropertiesfilepath");
					System.exit(1);
				}
			}
		}

		properties = loadProperties(propsFile);

		// first set debug and trace
		Res.setDebug(Convert.toBoolean(properties.getProperty("debug", "false")));
		Res.setTrace(Convert.toBoolean(properties.getProperty("trace", "false")));
		String targs = properties.getProperty("traceTargets");
		if (targs != null) {
			for (String targ: StringTools.split(targs, ",", true, false)) {
				Res.addTraceTarget(targ);
			}
		}
		Res.setLogStacktraces(Convert.toBoolean(properties.getProperty("logStacktraces", "false")));

		// configure logging - do first in case we need to log errors in config
		configureLogging(properties);

		// general properties
		configureGeneralParams(properties);

		// get db settings
		configureDatabase(properties);

		// configure work sources
		configureSources(properties);

		workMapFile = new File(homeDir, "tmp/workmap-" + httpJsonRpcPort + ".bin");
	}

	public Properties loadProperties(File propsFile) {
		properties = loadSingleProperties(propsFile);

		Res.setDebug(Convert.toBoolean(properties.getProperty("debug", "true")));

		String includes = properties.getProperty("include", null);
		if (includes != null) {
			boolean noConflicts = includes.trim().startsWith("!");
			boolean inheritNoConflicts = includes.trim().startsWith("!!");
			while (includes.trim().startsWith("!"))
				includes = includes.trim().substring(1);
			processIncludes(noConflicts, inheritNoConflicts, includes, propsFile.getParentFile());
		}

		return properties;
	}

	private void processIncludes(boolean noConflicts, boolean inheritNoConflicts, String includes, File parent) {
		if (!inheritNoConflicts) {
			noConflicts = includes.trim().startsWith("!");
			inheritNoConflicts = includes.trim().startsWith("!!");
		}

		while (includes.trim().startsWith("!"))
			includes = includes.trim().substring(1);

		if (includes.trim().startsWith("!"))
			includes = includes.trim().substring(1);
		List<String> includeList = StringTools.split(includes, ",", true, false);
		for (String include : includeList) {
			File file = new File(parent, include);
			Res.logInfo("Processing property include file: " + file.getAbsolutePath());
			if (!file.exists()) {
				Res.logError("Cannot find included properties file: " + file.getAbsolutePath());
				System.exit(1);
			}
			Properties newProps = loadSingleProperties(file);
			accumulateProperties(newProps, file, noConflicts);
			includes = newProps.getProperty("include", null);
			if (includes != null)
				processIncludes(noConflicts, inheritNoConflicts, includes, file.getParentFile());
		}
	}

	private String accumulateProperties(Properties extraProperties, File extraPropsFile, boolean noConflicts) {
		String includes = extraProperties.getProperty("include", null);
		for (String key : extraProperties.keySet()) {
			if (properties.put(key, extraProperties.get(key)) != null && noConflicts) {
				Res.logError("Conflicting property: \"" + key + "\" in file: " + extraPropsFile.getAbsolutePath());
				System.exit(1);
			}
		}
		return includes;
	}

	private Properties loadSingleProperties(File propsFile) {
		java.util.Properties p = new java.util.Properties();
		try {
			p.load(new FileInputStream(propsFile));
		} catch (FileNotFoundException e) {
			Res.logError("Could not find properties file.  Either create file with default name ("
					+ DEFAULT_PROPERTIES_FILE
					+ ") in same directory as runtime jar or specific on command line with option -Dconfig=mypropertiesfilepath");
			System.exit(1);
		} catch (IOException e) {
			Res.logError("Could not load properties file.", e);
			System.exit(1);
		}
		// a bit of a hack to get around java props not allowing null values
		Properties properties = new Properties();
		for (Object k : p.keySet()) {
			String key = String.valueOf(k);
			properties.put(key, p.getProperty(key));
		}
		return properties;
	}

	protected Properties getPropsForPrefix(String prefix, Properties props) {
		if (!prefix.endsWith("."))
			prefix += ".";
		Properties newProps = new Properties();
		for (Object k : props.keySet()) {
			String key = String.valueOf(k);
			// if (key.startsWith(prefix))
			// if (key == null)
			// ;
			// int x = 0;
			newProps.put(key, props.getProperty(key));
		}
		return newProps;
	}

	protected LinkedHashSet<String> getPropsForSource(String prefix, Properties props) {
		Properties p = getPropsForPrefix(prefix, props);
		LinkedHashSet<String> nums = new LinkedHashSet();
		for (Object k : p.keySet()) {
			if (k.toString().startsWith(prefix)) {
				try {
					String key = String.valueOf(k).replace(prefix, "");
					int firstDot = key.indexOf(".");
					if (firstDot == -1) {
						Res.logError("Invalid properties key: " + k);
						System.exit(1);
					}
					key = key.substring(firstDot + 1);
					firstDot = key.indexOf(".");
					if (firstDot == -1) {
						Res.logError("Invalid properties key: " + k);
						System.exit(1);
					}
					String num = key.substring(0, key.indexOf("."));
					boolean hasSuffix = false;
					try {
						Integer.parseInt(num);
						hasSuffix = true;
					} catch (Throwable t) {
					}
					if (hasSuffix) {
						nums.add(prefix + "." + num);
					} else
						nums.add(prefix);
				} catch (Throwable t) {
					Res.logError("Invalid properties key: " + k, t);
					System.exit(1);
				}
			}
		}
		return nums;
	}

	protected void doCommonSourceConfig(WorkSource source, String prefix, Properties p) {
		source.setCacheWaitTimeout(Convert.toInteger(p.getProperty(prefix + ".cacheWaitTimeout", "3000")));
		source.setMaxCacheSize(Convert.toInteger(p.getProperty(prefix + ".maxCacheSize", "20")));
		source.setMaxConcurrentDownloadRequests(Convert.toInteger(p.getProperty(prefix
				+ ".maxConcurrentDownloadRequests", "6")));
		source.setMaxConcurrentUpstreamSubmits(Convert.toInteger(p.getProperty(
				prefix + ".maxConcurrentUpstreamSubmits", "3")));
		source.setMaxWorkAgeToFlush(Convert.toLong(p.getProperty(prefix + ".maxWorkAgeToFlush", "20")) * 1000l);
		source.setMinIntervalBetweenHttpRequests(Convert.toLong(p.getProperty(prefix
				+ ".minIntervalBetweenHttpRequests", "50")));
		source.setMinIntervalBetweenHttpRequestsWhenFrantic(Convert.toLong(p.getProperty(prefix
				+ ".minIntervalBetweenHttpRequestsWhenFrantic", "10")));
		source.setMaxIntervalBetweenBlockCheck(Convert.toInteger(p.getProperty(prefix + ".blockmonitor.maxPollInterval", "100")));
		
		source.setNativeLongpoll(Convert.toBoolean(p.getProperty(prefix + ".longpoll.native.enable", "false")));
		source.setNativeLongpollVerificationEnabled(!Convert.toBoolean(p.getProperty(prefix + ".longpoll.native.disableVerification", "false")));
		source.setNativeLongpollPassphrase(p.getProperty(prefix + ".longpoll.native.passphrase", "null"));
		String allowedHosts = p.getProperty(prefix + ".longpoll.native.allowedHosts", "");
		source.getNativeLongpollAllowedHosts().addAll(StringTools.split(allowedHosts, ",", true, false));
	}

	protected void configureSources(Properties props) {
		// local sources first
		LinkedHashSet<String> nums = getPropsForSource("source.local", props);
		for (String prefix : nums) {
			Properties p = getPropsForPrefix(prefix, props);
			if (!"true".equalsIgnoreCase(p.getProperty(prefix + ".disabled"))) {
				String name = p.getProperty(prefix + ".name", prefix);
				String url = p.getProperty(prefix + ".url");
				String username = p.getProperty(prefix + ".username");
				String password = p.getProperty(prefix + ".password");

				if (username == null || password == null) {
					Res.logError("WARNING: source properties: username, password should be set!");
				}
				if (url == null) {
					Res.logError("source url for source: " + prefix + " (" + name + ") is not set");
					System.exit(1);
				}

				int weighting = Convert.toInteger(p.getProperty(prefix + ".weighting", "0"));
				DaemonSource source = poolServer.buildLocalDaemonSource(name, url, username, password, weighting);
				// new DaemonSource(poolServer.getWorkProxy(), name, url,
				// username, password, weighting);
				doCommonSourceConfig(source, prefix, p);

				// local only settings
				source.setAllowJsonRpcPassThru(Convert.toBoolean(p.getProperty(prefix + ".allowJSON-RPCPassthru",
						"false")));
				String allowedMethods = p.getProperty(prefix + ".allowedPassthruMethods");
				source.getAllowedPassThruMethods().clear();
				source.getAllowedPassThruMethods().addAll(StringTools.split(allowedMethods, ","));
				source.setRewriteDifficulty(Convert.toBoolean(p.getProperty(prefix + ".rewriteDifficulty", "true")));
				source.setRewriteDifficultyTarget(p.getProperty(prefix + ".rewriteDifficultyTarget",
						Res.getEasyDifficultyTargetAsString()));

				poolServer.getWorkProxy().registerDaemonSource(source);
			}
		}

		poolServer.getWorkProxy().calcWeightings();
	}

	private void configureLogging(Properties props) {

		File file;

		String tmp = props.getProperty("errorLogFile", null);
		if (tmp != null) {
			if (tmp.startsWith("/") || tmp.startsWith("\\")) {
				file = new File(tmp);
			} else {
				file = new File(homeDir, tmp);
			}
			try {
				if (!file.exists())
					file.createNewFile();
				System.setErr(new PrintStream(file));
			} catch (FileNotFoundException e) {
				Res.logException(e);
			} catch (IOException e) {
				Res.logException(e);
			}
		}

		tmp = props.getProperty("stdOutLogFile", null);

		if (tmp != null) {
			if (tmp.startsWith("/") || tmp.startsWith("\\")) {
				file = new File(tmp);
			} else {
				file = new File(homeDir, tmp);
			}
			try {
				if (!file.exists())
					file.createNewFile();
				System.setOut(new PrintStream(file));
			} catch (FileNotFoundException e) {
				Res.logException(e);
			} catch (IOException e) {
				Res.logException(e);
			}
		}

		tmp = props.getProperty("requestsLogFile", null);

		if (tmp != null) {
			if (tmp.startsWith("/") || tmp.startsWith("\\")) {
				file = new File(tmp);
			} else {
				file = new File(homeDir, tmp);
			}
			poolServer.getWorkRequestLogger().setOutputFile(file);
		} else {
			poolServer.getWorkRequestLogger().setOutputFile(null);
		}

		tmp = props.getProperty("sharesLogFile", null);

		if (tmp != null) {
			if (tmp.startsWith("/") || tmp.startsWith("\\")) {
				file = new File(tmp);
			} else {
				file = new File(homeDir, tmp);
			}
			poolServer.getShareLogger().setOutputFile(file);
		} else {
			poolServer.getShareLogger().setOutputFile(null);
		}

		boolean ppCompatible = Convert.toBoolean(props.getProperty("usePushPoolCompatibleFormat", "false"));

		// request logger
		poolServer.getWorkRequestLogger().setUsePushPoolCompatibleFormat(ppCompatible);
		poolServer.getWorkRequestLogger().setFlushFilesImmedate(
				Convert.toBoolean(props.getProperty("request.flushFilesImmedate", "false")));
		poolServer.getWorkRequestLogger().setMaxRequestsToQueueBeforeCommit(
				Convert.toInteger(props.getProperty("request.maxRequestsToQueueBeforeCommit", "10")));
		poolServer.getWorkRequestLogger().setMaxRequestAgeBeforeCommit(
				Convert.toInteger(props.getProperty("request.maxRequestAgeBeforeCommit", "10")) * 1000);
		Res.setLogRequestsToStdout(Convert.toBoolean(props.getProperty("requestsToStdout", "false")));

		poolServer.getShareLogger().setUsePushPoolCompatibleFormat(ppCompatible);
		poolServer.getShareLogger().setFlushFilesImmedate(
				Convert.toBoolean(props.getProperty("shares.flushToFilesImmedate", "false")));
		poolServer.getShareLogger().setMaxEntrysToQueueBeforeCommit(
				Convert.toInteger(props.getProperty("shares.maxEntrysToQueueBeforeCommit", "10")));
		poolServer.getShareLogger().setMaxEntryAgeBeforeCommit(
				Convert.toInteger(props.getProperty("shares.maxEntryAgeBeforeCommit", "10")) * 1000);

		Res.setLogSharesToStdout(Convert.toBoolean(props.getProperty("sharesToStdout", "false")));

		// this is set per source so no needed.
		// int concurrentDownloadRequests =
		// Convert.toInteger(props.getProperty("submit.maxConcurrentSubmitRequests",
		// "2"));
		// concurrentDownloadRequests =
		// enforceBounds(concurrentDownloadRequests, 1, 10);
		// poolServer.getWorkResultSubmitter().setMaxConcurrentDownloadRequests(concurrentDownloadRequests);

		long minIntervalBetweenHttpRequests = Convert.toLong(props.getProperty("submit.minIntervalBetweenHttpRequests",
				"50"));
		minIntervalBetweenHttpRequests = enforceBounds(minIntervalBetweenHttpRequests, 0, 1000);
		poolServer.getShareSubmitter().setMinIntervalBetweenHttpRequests(minIntervalBetweenHttpRequests);

		int maxSubmitRetryOnConnectionFail = Convert
				.toInteger(props.getProperty("maxSubmitRetryOnConnectionFail", "2"));
		maxSubmitRetryOnConnectionFail = enforceBounds(maxSubmitRetryOnConnectionFail, 0, 3);
		poolServer.getShareSubmitter().setMaxSubmitRetryOnConnectionFail(maxSubmitRetryOnConnectionFail);

		// this shouldn't be a public param.
		// poolServer.getWorkProxy().setMaxAgeToMapWorkSource(maxAgeToMapWorkSource)

	}

	private long enforceBounds(long val, long min, long max) {
		if (val < min)
			return min;
		if (val > max)
			return max;
		return val;
	}

	private int enforceBounds(int val, int min, int max) {
		if (val < min)
			return min;
		if (val > max)
			return max;
		return val;
	}

	public void configureHtpListenerParams(Properties props) {
		if (Convert.toBoolean(props.getProperty("useRidiculouslyEasyTargetForTesingButDONTIfThisIsARealPool", "false"))) {
			Res.setEasytDifficultyTarget(PSJBlock.EASIEST_DIFFICULTY_TARGET());
			Res.logInfo("easy target set to: " + Res.getEasyDifficultyTargetAsString());
		}
		safeRestart = Convert.toBoolean(props.getProperty("enableSafeRestart", "true"));
		forceAllSubmitsUpstream = Convert.toBoolean(props.getProperty("forceAllSubmitsUpstream", "false"));
		flushCacheInterval = Convert.toInteger(props.getProperty("flushCacheInterval", "5")) * 1000;
		workerCacheExpiry = Convert.toInteger(props.getProperty("workerCacheExpiry", "120")) * 1000;
		poolServer.getWorkerProxy().setCaseInsensitiveWorkers(!Convert.toBoolean(props.getProperty("caseSensitiveWorkerNames", "false")));
		useFixedTimeWorkerCacheExpiry = Convert.toBoolean(props.getProperty("useBrokenWorkerCacheEvictionStrategyBecauseImTooLazyToAdjustMyFrontendToUseTheCacheProperly","false"));
		enableManagentInterface = Convert.toBoolean(props.getProperty("enableManagementInterface", "false"));
		allowShutdownByManagementInterface = Convert.toBoolean(props.getProperty("allowShutdownByManagementInterface",
				"false"));
		managementInterfacePort = Convert.toInteger(props.getProperty("managementInterfacePort", "8331"));
		bindManagementInterfaceOnlyToLocahost = Convert.toBoolean(props.getProperty(
				"bindManagementInterfaceOnlyToLocahost", "false"));
		String allowed = props.getProperty("allowedManagementAddresses");
		allowedManagementAddresses.clear();
		if (allowed != null) {
			allowedManagementAddresses.addAll(StringTools.split(allowed, ","));
		}

		enableQoS = Convert.toBoolean(props.getProperty("enableQoS", "false"));
		QoSMaxRequestsToServiceConcurrently = Convert.toInteger(props.getProperty(
				"QoSMaxRequestsToServiceConcurrently", "10"));
		enableDoSFilter = Convert.toBoolean(props.getProperty("enableDoSFilter", "false"));
		DoSFilterMaxRequestsPerSecondBeforeThrottle = Convert.toInteger(props.getProperty(
				"DoSFilterMaxRequestsPerSecondBeforeThrottle", "4"));

		boolean badInit = true;
		try {
			httpJsonRpcPath = props.getProperty("listen.http.json-rpc.path");
			httpJsonRpcPort = Convert.toInteger(props.getProperty("listen.http.json-rpc.port"));

			httpLPJsonRpcUrl = props.getProperty("listen.longpoll.http.json-rpc.url");
			httpLPTimeout = Convert.toInteger(props.getProperty("listen.longpoll.http.json-rpc.timeout", "600")) * 1000;
			httpLPMaxConnections = Convert.toInteger(props.getProperty("listen.longpoll.http.json-rpc.maxConnections",
					"1000"));
			if (httpLPJsonRpcUrl != null) {
				try {
					URL url = new URL(httpLPJsonRpcUrl);
					httpLPJsonRpcPath = url.getPath();
					if (httpLPJsonRpcPath != null && !httpLPJsonRpcPath.endsWith("/")) {
						httpLPJsonRpcPath += "/";
						if (httpLPJsonRpcUrl.indexOf("?") == -1) {
							httpLPJsonRpcUrl += "/";
						} else {
							int queryStart = httpLPJsonRpcUrl.indexOf("?");
							httpLPJsonRpcUrl = httpLPJsonRpcUrl.substring(0, queryStart) + "/"
									+ httpLPJsonRpcUrl.substring(queryStart + 1);
						}
					}
					httpLPJsonRpcPort = url.getPort();
				} catch (MalformedURLException e) {
					Res.logError("could not parse LongPoll URL: " + httpLPJsonRpcUrl + " longpolling disabled: "
							+ e.getMessage());
					httpLPJsonRpcUrl = null;
				}
			}
			// httpLPJsonRpcPort =
			// Convert.toInteger(props.getProperty("listen.longpoll.http.json-rpc.port"));
			enableLongpoll = Convert.toBoolean(props.getProperty("listen.longpoll.enable"));
			longpollMaxConnectionsPerWorker = Convert.toInteger(props.getProperty("listen.longpoll.defaultMaxLPConnectionsPerWorker", "-1"));
			nativeLongpollPort = Convert.toInteger(props.getProperty("native.longpoll.port", "8950"));
			nativeLongpollTimeout = Convert.toInteger(props.getProperty("native.longpoll.timeout", "2000"));
			if (httpJsonRpcPath != null && httpLPJsonRpcUrl != null)
				badInit = false;
		} catch (Throwable t) {
			Res.logException(t);
		}
		if (badInit) {
			Res.logError("Invalid listen properties.  The following properties must be initialized: listen.http.json-rpc.port, listen.http.json-rpc.path, listen.longpoll.enable, listen.longpoll.http.json-rpc.port, listen.longpoll.http.json-rpc.path");
			System.exit(1);
		}
	}

	private void configureGeneralParams(Properties props) {

		configureHtpListenerParams(props);

		String pid = props.getProperty("pidFile", "tmp/poolserverj-" + httpJsonRpcPort + ".pid");
		File pidFile = new File(homeDir, pid);
		if (pidFile.getParentFile() != null) {
			pidFile.getParentFile().mkdirs();
		}
		pidFile.delete();
		String name = ManagementFactory.getRuntimeMXBean().getName();
		int p = name.indexOf('@');
		if (p > 0) {
			String pidString = name.substring(0, p);
			FileUtil.saveStringAsFile(pidString, pidFile, false);
			Res.logDebug("Wrote PID: " + pidString + " to pidFile: " + pidFile.getAbsolutePath());
		} else {
			Res.logWarn("Could not detemine JVM Process id, pidFile will not be written");
		}


		String tmp = props.getProperty("notify.blockchange.method", null);
		if ("httpget".equalsIgnoreCase(tmp)) {
			tmp = props.getProperty("notify.blockchange.url", null);
			long delay = Convert.toLong(props.getProperty("notify.blockchange.delay", "5000"));
			if (tmp != null) {
				NotifyBlockChangeMethod method = new HttpGetNotifyBlockChangeMethod(tmp);
				poolServer.getBlockTracker().setNotifyBlockChangeMethod(method);
				poolServer.getBlockTracker().setNotifyBlockChangedelay(delay);
			}
		}
		poolServer.getWorkProxy().setAllowBreakWeightingRulesAfterBlockChange(
				Convert.toBoolean(props.getProperty("allowBreakWeightingRulesAfterBlockChange", "true")));

		// mining extensions and memory management
		long maxAgeToMapWorkSource = 130000;
		boolean useCompressedMapKeys = false;
		float nonceRangePaddingFactor = 2.0f;
		long rollNTimeExpire = 120000;

		maxAgeToMapWorkSource = Convert.toLong(props.getProperty("maxAgeToMapWorkSource", "130"));
		if (maxAgeToMapWorkSource > 0)
			maxAgeToMapWorkSource = maxAgeToMapWorkSource * 1000;
		poolServer.getWorkProxy().setMaxAgeToMapWorkSource(maxAgeToMapWorkSource);

		useCompressedMapKeys = Convert.toBoolean(props.getProperty("useCompressedMapKeys", "false"));
		poolServer.getWorkProxy().setUseCompressedMapKeys(useCompressedMapKeys);

		nonceRangePaddingFactor = Convert.toFloat(props.getProperty("nonceRangePaddingFactor", "2.0"));
		poolServer.getWorkProxy().setNonceRangePaddingFactor(nonceRangePaddingFactor);

		rollNTimeExpire = Convert.toLong(props.getProperty("rollNTimeExpire", "120"));
		if (rollNTimeExpire > 0) {
			rollNTimeString = "expire=" + rollNTimeExpire;
			rollNTimeExpire = rollNTimeExpire * 1000;
			if (rollNTimeExpire == maxAgeToMapWorkSource && maxAgeToMapWorkSource > 0) {
				Res.logWarn("rollNTimeExpire == maxAgeToMapWorkSource, it is recommended to set maxAgeToMapWorkSource a few seconds higher than rollNTime expire");
			}
			if (rollNTimeExpire > maxAgeToMapWorkSource && maxAgeToMapWorkSource > 0) {
				Res.logError("rollNTimeExpire > maxAgeToMapWorkSource.  This will result in miners getting stale work responses for valid shares");
			}
		}
		poolServer.getWorkProxy().setRollNTimeExpire(rollNTimeExpire);

	}

	private void configureDatabase(Properties props) {
		String engine = props.getProperty("db.engine");
		if (engine == null) {
			Res.logError("Database engine not set, database logging disabled.");
			return;
		}
		String host = props.getProperty("db.host", "localhost");
		Integer port = Convert.toInteger(props.getProperty("db.port", null));
		String db = props.getProperty("db.name");
		String schema = props.getProperty("db.schema");
		String file = props.getProperty("db.file");
		String user = props.getProperty("db.user");
		String password = props.getProperty("db.password");
		String url = props.getProperty("db.url");
		String connectionOptions = props.getProperty("db.connectionOptions");

		String wengine = props.getProperty("db.worker.engine", engine);
		String whost = props.getProperty("db.worker.host", host);
		Integer wport = Convert.toInteger(props.getProperty("db.worker.port", String.valueOf(port)));
		String wdb = props.getProperty("db.worker.name", db);
		String wschema = props.getProperty("db.worker.schema", schema);
		String wfile = props.getProperty("db.worker.file", file);
		String wuser = props.getProperty("db.worker.user", user);
		String wpassword = props.getProperty("db.worker.password", password);
		String wurl = props.getProperty("db.worker.url", url);
		String wConnectionOptions = props.getProperty("db.worker.connectionOptions", connectionOptions);

		String sengine = props.getProperty("db.shares.engine", engine);
		String shost = props.getProperty("db.shares.host", host);
		Integer sport = Convert.toInteger(props.getProperty("db.shares.port", String.valueOf(port)));
		String sdb = props.getProperty("db.shares.name", db);
		String sschema = props.getProperty("db.shares.schema", schema);
		String sfile = props.getProperty("db.shares.file", file);
		String suser = props.getProperty("db.shares.user", user);
		String spassword = props.getProperty("db.shares.password", password);
		String surl = props.getProperty("db.shares.url", url);
		String sConnectionOptions = props.getProperty("db.shares.connectionOptions", connectionOptions);
		
		if ("mysql".equalsIgnoreCase(wengine)) {
			workerSql = new MySql(whost, wport == null ? "3306" : String.valueOf(wport), wschema, wuser, wpassword);
		} else if ("postgresql".equalsIgnoreCase(wengine)) {
			workerSql = new PostgreSql(whost, wport == null ? "5432" : String.valueOf(wport), wdb, wschema, wuser,
					wpassword);
		} else if ("sqlite3".equalsIgnoreCase(wengine)) {
			workerSql = new Sqlite3(wfile);
		}
		if (workerSql != null) {
			List<String> options = StringTools.split(wConnectionOptions, ",", true, false);
			HashMap<String, String> optionMap = workerSql.getJdbcOptionMap();
			for (String option: options) {
				String[] parts = option.split("=");
				if (parts.length != 2) {
					Res.logError("Badly formatted JDBC option: " + option);
					System.exit(1);
				}
				optionMap.put(parts[0], parts[1]);
			}
		}

		if ("mysql".equalsIgnoreCase(sengine)) {
			sharesSql = new MySql(shost, sport == null ? "3306" : String.valueOf(sport), sschema, suser, spassword);
			// sharesSql.getJdbcOptionMap().remove("useCompression");
			// sharesSql.getJdbcOptionMap().put("allowUrlInLocalInfile",
			// "true");
		} else if ("postgresql".equalsIgnoreCase(sengine)) {
			sharesSql = new PostgreSql(shost, sport == null ? "5432" : String.valueOf(sport), sdb, sschema, suser,
					spassword);
		} else if ("sqlite3".equalsIgnoreCase(sengine)) {
			sharesSql = new Sqlite3(sfile);
		}
		if (sharesSql != null) {
			List<String> options = StringTools.split(sConnectionOptions, ",", true, false);
			HashMap<String, String> optionMap = sharesSql.getJdbcOptionMap();
			for (String option: options) {
				String[] parts = option.split("=");
				if (parts.length != 2) {
					Res.logError("Badly formatted JDBC option: " + option);
					System.exit(1);
				}
				optionMap.put(parts[0], parts[1]);
			}
		}
		
		if (workerSql != null)
			workerSql.getJdbcOptionMap().put("autoReconnect", "true");
		if (sharesSql != null)
			sharesSql.getJdbcOptionMap().put("autoReconnect", "true");

		if (workerSql != null) {
			try {
				Res.logDebug("Connecting to DB URL: " + workerSql.getUrl());
				workerSql.prepareConnection();
				if (sharesSql != null)
					sharesSql.prepareConnection();
			} catch (SQLException e) {
				Res.logError("Failed to start database connection", e);
				System.exit(1);
				return;
			}
		}

		selectUserQuery = props.getProperty("db.stmt.selectWorker");
		selectUserListQuery = props.getProperty("db.stmt.selectWorkerList");
		insertShareQuery = props.getProperty("db.stmt.insertShare");
		poolServer.getShareLogger().setInsertQuery(insertShareQuery);
		poolServer.getShareLogger().setUpdateCounters(
				Convert.toBoolean(props.getProperty("db.updateShareCounters", "false")));

		usernameColumn = props.getProperty("db.column.username", "username");
		userPasswordColumn = props.getProperty("db.column.password", "password");
		userIdColumn = props.getProperty("db.column.id", "id");
		userAllowedHostsColumn = props.getProperty("db.column.allowHosts", null);

		String workerEngine = props.getProperty("db.engine.workerFetch", null);
		String workerEngineExtraParams = props.getProperty("db.engine.workerFetch.extraParams", null);
		if (workerEngine != null) {
			try {
				String[] extraParams = new String[0];
				if (workerEngineExtraParams != null)
					extraParams = StringTools.splitToArray(workerEngineExtraParams, ",", true, true);
				Class<? extends WorkerDBFetchEngine> workEngClass = (Class<? extends WorkerDBFetchEngine>) Class
						.forName(workerEngine);
				Constructor con = workEngClass.getConstructor(String[].class);
				WorkerDBFetchEngine workEng = (WorkerDBFetchEngine) con.newInstance((Object) extraParams);
				poolServer.getWorkerProxy().setWorkerDBFetchEngine(workEng);
			} catch (ClassNotFoundException e) {
				Res.logError("Could not find worker fetch engine class [" + workerEngine
						+ ".  Check that it is defined using a fully qualified name", e);
				System.exit(1);
			} catch (InstantiationException e) {
				Res.logError("Could not create worker fetch engine class [" + workerEngine
						+ ".  If this is a cusom engine ensure it has an empty constructor", e);
				System.exit(1);
			} catch (IllegalAccessException e) {
				e.printStackTrace();
				System.exit(1);
			} catch (SecurityException e) {
				e.printStackTrace();
				System.exit(1);
			} catch (NoSuchMethodException e) {
				e.printStackTrace();
				System.exit(1);
			} catch (IllegalArgumentException e) {
				e.printStackTrace();
				System.exit(1);
			} catch (InvocationTargetException e) {
				e.printStackTrace();
				System.exit(1);
			}
		}

		String shareFlushEngine = props.getProperty("db.engine.shareLogging", null);
		String shareFlushEngineExtraParams = props.getProperty("db.engine.shareLogging.extraParams", null);
		if (shareFlushEngine != null) {
			try {
				String[] extraParams = new String[0];
				if (shareFlushEngineExtraParams != null)
					extraParams = StringTools.splitToArray(shareFlushEngineExtraParams, ",", true, true);

				Class<? extends DefaultPreparedStatementSharesDBFlushEngine> shareEngClass = (Class<? extends DefaultPreparedStatementSharesDBFlushEngine>) Class
						.forName(shareFlushEngine);
				String tempFilename = props.getProperty("db.engine.shareLogging.tempfile", null);
				Constructor con = shareEngClass.getConstructor(ShareLogger.class, String.class, String[].class);
				DefaultPreparedStatementSharesDBFlushEngine shareEng = (DefaultPreparedStatementSharesDBFlushEngine) con
						.newInstance(poolServer.getShareLogger(), tempFilename, extraParams);
				poolServer.getShareLogger().setDbFlushEngine(shareEng);
			} catch (ClassNotFoundException e) {
				Res.logError("Could not find database flush engine class [" + shareFlushEngine
						+ ".  Check that it is defined using a fully qualified name", e);
				System.exit(1);
			} catch (InstantiationException e) {
				Res.logError(
						"Could not create database flush engine class ["
								+ shareFlushEngine
								+ ".  If this is a custom engine ensure it has a constructor with two parameters: (ShareLogger logger, String tempFileName)",
						e);
				System.exit(1);
			} catch (IllegalAccessException e) {
				e.printStackTrace();
				System.exit(1);
			} catch (SecurityException e) {
				e.printStackTrace();
				System.exit(1);
			} catch (NoSuchMethodException e) {
				Res.logError(
						"Could not create database flush engine class ["
								+ shareFlushEngine
								+ ".  If this is a custom engine ensure it has a constructor with two parameters: (ShareLogger logger, String tempFileName)",
						e);
				System.exit(1);
			} catch (IllegalArgumentException e) {
				e.printStackTrace();
				System.exit(1);
			} catch (InvocationTargetException e) {
				e.printStackTrace();
				System.exit(1);
			}
		} else {
			// set default flushEngine
			poolServer.getShareLogger().setDbFlushEngine(
					new DefaultPreparedStatementSharesDBFlushEngine(poolServer.getShareLogger(), null, new String[0]));
		}

		String authEngine = props.getProperty("authenticatorEngine", null);
		String authEngineExtraParams = props.getProperty("authenticatorEngine.extraParams", null);

		if (authEngine != null) {
			try {
				String[] extraParams = new String[0];
				if (authEngineExtraParams != null)
					extraParams = StringTools.splitToArray(authEngineExtraParams, ",", true, true);

				Class<? extends WorkerAuthenticator> authEngClass = (Class<? extends WorkerAuthenticator>) Class
						.forName(authEngine);
				Constructor con = authEngClass.getConstructor(WorkerProxy.class, String[].class);
				WorkerAuthenticator authEng = (WorkerAuthenticator) con.newInstance(poolServer.getWorkerProxy(),
						extraParams);
				workerAuthenticator = authEng;
			} catch (ClassNotFoundException e) {
				Res.logError("Could not find authenticator engine class [" + authEngine
						+ ".  Check that it is defined using a fully qualified name", e);
				System.exit(1);
			} catch (InstantiationException e) {
				Res.logError(
						"Could not create authenticator engine class ["
								+ authEngine
								+ ".  If this is a custom engine ensure it has a constructor with one parameter of WorkerProxy class (see comments in WorkerAuthenticator class for an example",
						e);
				System.exit(1);
			} catch (IllegalAccessException e) {
				e.printStackTrace();
				System.exit(1);
			} catch (SecurityException e) {
				e.printStackTrace();
			} catch (NoSuchMethodException e) {
				Res.logError(
						"Could not create authenticator engine class ["
								+ authEngine
								+ ".  If this is a cusom engine ensure it has a constructor with one parameter of WorkerProxy class (see comments in WorkerAuthenticator class for an example",
						e);
				System.exit(1);
			} catch (IllegalArgumentException e) {
				e.printStackTrace();
				System.exit(1);
			} catch (InvocationTargetException e) {
				e.printStackTrace();
				System.exit(1);
			}
		} else {
			workerAuthenticator = new WorkerAuthenticator(poolServer.getWorkerProxy(), null);
		}
	}

	public static Sql getWorkerSql() {
		return conf.workerSql;
	}

	public static Sql getSharesSql() {
		return conf.sharesSql;
	}

	public static String getSqlSelectUser() {
		return conf.selectUserQuery;
	}
	
	public static String getSqlSelectUserList() {
		return conf.selectUserListQuery;
	}

	public static String getSqlInsertShare() {
		return conf.insertShareQuery;
	}

	public static long getWorkerCacheExpiry() {
		return conf.workerCacheExpiry;
	}
	
	public static boolean isUseFixedTimeWorkerCacheExpiry() {
		return conf.useFixedTimeWorkerCacheExpiry;
	}

	public static String getUsernameColumn() {
		return conf.usernameColumn;
	}

	public static String getUserPasswordColumn() {
		return conf.userPasswordColumn;
	}

	public static String getUserIdColumn() {
		return conf.userIdColumn;
	}

	public static String getUserAllowedHostsColumn() {
		return conf.userAllowedHostsColumn;
	}

	public static long getFlushCacheInterval() {
		return conf.flushCacheInterval;
	}
	
	public static int getDefaultMaxLPConnectionsPerWorker() {
		return conf.longpollMaxConnectionsPerWorker;
	}

	/**
	 * @return the httpJsonRpcPort
	 */
	public int getHttpJsonRpcPort() {
		return httpJsonRpcPort;
	}

	/**
	 * @return the httpJsonRpcPath
	 */
	public String getHttpJsonRpcPath() {
		return httpJsonRpcPath;
	}

	/**
	 * @return the httpLPJsonRpcPort
	 */
	public int getHttpLPJsonRpcPort() {
		return httpLPJsonRpcPort;
	}

	/**
	 * @return the httpLPJsonRpcPath
	 */
	public String getHttpLPJsonRpcUrl() {
		return httpLPJsonRpcUrl;
	}

	/**
	 * @return the httpLPJsonRpcPath
	 */
	public String getHttpLPJsonRpcPath() {
		return httpLPJsonRpcPath;
	}

	/**
	 * @return the enableLongpoll
	 */
	public boolean isEnableLongPoll() {
		return enableLongpoll;
	}

	/**
	 * @return the enableManagentInterface
	 */
	public boolean isEnableManagentInterface() {
		return enableManagentInterface;
	}

	/**
	 * @return the managementInterfacePort
	 */
	public int getManagementInterfacePort() {
		return managementInterfacePort;
	}

	/**
	 * @return the allowedManagementAddresses
	 */
	public HashSet<String> getAllowedManagementAddresses() {
		return allowedManagementAddresses;
	}

	/**
	 * @return the httpLPTimeout
	 */
	public int getHttpLPTimeout() {
		return httpLPTimeout;
	}

	/**
	 * @return the httpLPMaxConnections
	 */
	public int getHttpLPMaxConnections() {
		return httpLPMaxConnections;
	}

	/**
	 * @return the bindManagementInterfaceOnlyToLocahost
	 */
	public boolean isBindManagementInterfaceOnlyToLocahost() {
		return bindManagementInterfaceOnlyToLocahost;
	}

	/**
	 * @return the allowShutdownByManagementInterface
	 */
	public boolean isAllowShutdownByManagementInterface() {
		return allowShutdownByManagementInterface;
	}

	/**
	 * @return the enableQoS
	 */
	public boolean isEnableQoS() {
		return enableQoS;
	}

	/**
	 * @return the enableDoSFilter
	 */
	public boolean isEnableDoSFilter() {
		return enableDoSFilter;
	}

	/**
	 * @return the safeRestart
	 */
	public boolean isSafeRestart() {
		return safeRestart;
	}

	/**
	 * @return the doSFilterMaxRequestsPerSecondBeforeThrottle
	 */
	public int getDoSFilterMaxRequestsPerSecondBeforeThrottle() {
		return DoSFilterMaxRequestsPerSecondBeforeThrottle;
	}

	/**
	 * @return the qoSMaxRequestsToServiceConcurrently
	 */
	public int getQoSMaxRequestsToServiceConcurrently() {
		return QoSMaxRequestsToServiceConcurrently;
	}

	/**
	 * max multiwork value to request
	 * 
	 * @return
	 */
	public static int getMaxMultiWork() {
		// TODO Auto-generated method stub
		return 10;
	}

	public File getWorkMapFile() {
		return workMapFile;
	}

	/**
	 * @return the rollNTimeString
	 */
	public String getRollNTimeString() {
		return rollNTimeString;
	}
	
	

	/**
	 * @return the logJettyStats
	 */
	public boolean isLogJettyStats() {
		return logJettyStats;
	}

	/**
	 * @param logJettyStats the logJettyStats to set
	 */
	public void setLogJettyStats(boolean logJettyStats) {
		this.logJettyStats = logJettyStats;
	}

	/**
	 * @deprecated
	 * @return
	 */
	public boolean isForceAllSubmitsUpstream() {
		return forceAllSubmitsUpstream;
	}

	/**
	 * @param forceAllSubmitsUpstream
	 *            the forceAllSubmitsUpstream to set
	 * @deprecated
	 */
	public void setForceAllSubmitsUpstream(boolean forceAllSubmitsUpstream) {
		this.forceAllSubmitsUpstream = forceAllSubmitsUpstream;
	}
	
	public WorkerAuthenticator getWorkerAuthenticator() {
		return workerAuthenticator;
	}

	public int getNativeLongpollPort() {
		return nativeLongpollPort;
	}

	public int getNativeLongpollTimeout() {
		return nativeLongpollTimeout;
	}
	
	/**
	 * @return the homeDir
	 */
	public static File getHomeDir() {
		return conf.homeDir;
	}



	public class Properties extends HashMap<String, String> {

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.util.Properties#getProperty(java.lang.String)
		 */
		public String getProperty(String key) {
			String s = super.get(key);
			return s == null ? null : "~#EMPTY_STRING#~".equals(s) ? "" : s.isEmpty() ? null : s;
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.util.Properties#getProperty(java.lang.String,
		 * java.lang.String)
		 */
		public String getProperty(String key, String defaultValue) {
			// TODO Auto-generated method stub
			String s = super.get(key);
			return s == null ? defaultValue : "~#EMPTY_STRING#~".equals(s) ? "" : s.isEmpty() ? defaultValue : s;
		}

	}

}
