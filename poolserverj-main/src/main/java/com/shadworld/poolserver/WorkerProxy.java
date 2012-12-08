package com.shadworld.poolserver;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import com.shadworld.poolserver.conf.Conf;
import com.shadworld.poolserver.conf.Res;
import com.shadworld.poolserver.db.worker.WorkerDBFetchEngine;
import com.shadworld.poolserver.entity.Worker;
import com.shadworld.poolserver.servlet.auth.WorkerAuthenticator;
import com.shadworld.poolserver.source.WorkEntry;
import com.shadworld.poolserver.stats.Stats;
import com.shadworld.sql.Sql;
import com.shadworld.utils.StringTools;

/**
 * proxys access to users in database and provides a local cache;
 * 
 * @author git
 * 
 */
public class WorkerProxy {

	final private Map<String, WorkerEntry> cache = Collections.synchronizedMap(new HashMap());
	final private Map<String, WorkerEntry> badWorkers = Collections.synchronizedMap(new HashMap());

	private WorkerDBFetchEngine workerDBEngine = new WorkerDBFetchEngine(null);
	private WorkerAuthenticator workerAuthenticator;
	
	private boolean idWorkerByName = true;
	
	private boolean caseInsensitiveWorkers = true;

	public WorkerProxy(PoolServer poolServer) {
		// TODO Auto-generated constructor stub
	}

	public void registerValidWork(String username) {
		if (caseInsensitiveWorkers)
			username = username == null ? null : username.toLowerCase();
		WorkerEntry entry = cache.get(username);
		if (entry == null)
			return;
		entry.worker.registerValidWork();
	}

	public int workDoneByWorker(String username) {
		if (caseInsensitiveWorkers)
				username = username == null ? null : username.toLowerCase();
		WorkerEntry entry = cache.get(username);
		if (entry == null)
			return 0;
		return entry.worker.workDoneByWorker();
	}

	public void notifyBlockChange() {
		for (WorkerEntry entry : cache.values()) {
			entry.worker.notifyBlockChange();
		}
	}

	public Worker getWorker(String username) {
		if (username == null)
			return null;
		
		//we need a canonical version of username to synchronize on.
		//this is for the rare case where a 2nd request comes in from the same
		//user while the 1st request is still waiting for the DB to return it.
		//canonizing the string allows us to lock granularly per user rather than synchronizing 
		//this whole method.
		if (caseInsensitiveWorkers)
			username = username.toLowerCase();
		//we add psj as a prefix in case the username is a common word which
		//*may* happen be used in a lock by one our dependent libs.  It's very unlikely
		//this will ever happen but if it does it will be a bitch to debug.
		String canonicalLock = ("psj^" + username).intern();
		synchronized (canonicalLock) {
			Worker worker = getWorkerFromCache(username);
			if (worker != null) {
				Stats.get().registerWorkerCacheHit(worker);
				workerAuthenticator.onWorkerCacheHit();
				return worker;
			}
			// check badWorker cache to make sure we don't search the DB over
			// and over
			// for a non existent worker.
			WorkerEntry entry = getBadWorkerFromCache(username);
			if (entry != null) {
				workerAuthenticator.onBadWorkerCacheHit();
				return entry.worker;
			}

			if (Res.isDebug())
				Res.logInfo("Retrieving worker from database: " + username);
			
			try {

				worker = workerDBEngine.fetchWorker(username);
				if (worker != null) {
					if (worker.getMaxLongpollConnections() <= 0) {
						worker.setMaxLongpollConnections(Conf.getDefaultMaxLPConnectionsPerWorker());
					}
					cacheWorker(worker);
					return worker;
				}

				Stats.get().registerWorkerNotFound(username);
				registerBadWorker(username, true);
				return null;
			} catch (Exception e) {
				Res.logError("Error attempting to retrieve user from database.", e);
				Stats.get().registerWorkerDataBaseFail(username, e);
				return null;
			}
		}

	}
	
	private void registerBadWorker(String username, boolean log) {
		Worker worker = new Worker();
		worker.setUsername(username);
		worker.setHostCheckingEnabled(false);
		worker.setBadWorker(true);
		WorkerEntry badWorker = new WorkerEntry(worker, System.currentTimeMillis());
		if (log && Res.isDebug())
			Res.logInfo("Caching unknown worker: " + username);
		badWorkers.put(username, badWorker);
	}

	private void cacheWorker(Worker worker) {
		synchronized (this) {
			if (worker == null || worker.getUsername() == null)
				return;
			WorkerEntry entry = new WorkerEntry(worker, System.currentTimeMillis());
			cache.put(worker.getUsername().toLowerCase(), entry);
		}
	}

	public void removeWorker(String username) {
		if (caseInsensitiveWorkers)
			username = username == null ? null : username.toLowerCase();
		synchronized (this) {
			cache.remove(username);
			badWorkers.remove(username);
		}
	}

	public void flushCache() {
		synchronized (this) {
			ArrayList<String> removed = new ArrayList();
			for (String name : cache.keySet()) {
				WorkerEntry entry = cache.get(name);
				if (entry == null || System.currentTimeMillis() > entry.fetchTime + Conf.getWorkerCacheExpiry())
					removed.add(name);
			}
			for (int i = 0; i < removed.size(); i++)
				cache.remove(removed.get(i));
			removed.clear();
			for (String name : badWorkers.keySet()) {
				WorkerEntry entry = badWorkers.get(name);
				if (entry == null || System.currentTimeMillis() > entry.fetchTime + Conf.getWorkerCacheExpiry())
					removed.add(name);
			}
			for (int i = 0; i < removed.size(); i++)
				badWorkers.remove(removed.get(i));
		}
	}

	private Worker getWorkerFromCache(String name) {
		synchronized (this) {
			WorkerEntry entry = cache.get(name);
			if (entry != null) {
				long now = System.currentTimeMillis();
				if (now < entry.fetchTime + Conf.getWorkerCacheExpiry()) {
					if (!Conf.isUseFixedTimeWorkerCacheExpiry())
						entry.fetchTime = now;
					return entry.worker;
				} else
					cache.remove(name);
			}
		}
		return null;
	}

	private WorkerEntry getBadWorkerFromCache(String name) {
		synchronized (this) {
			WorkerEntry entry = badWorkers.get(name);
			if (entry != null) {
				entry.fetchTime = System.currentTimeMillis();
				return entry;
			}
		}
		return null;
	}

	public void setWorkerDBFetchEngine(WorkerDBFetchEngine engine) {
		this.workerDBEngine = engine;
	}

	/**
	 * @param workerAuthenticator
	 *            the workerAuthenticator to set
	 */
	public void setWorkerAuthenticator(WorkerAuthenticator workerAuthenticator) {
		this.workerAuthenticator = workerAuthenticator;
	}
	
	/**
	 * @param caseInsensitiveWorkers the caseInsensitiveWorkers to set
	 */
	public void setCaseInsensitiveWorkers(boolean caseInsensitiveWorkers) {
		this.caseInsensitiveWorkers = caseInsensitiveWorkers;
	}



	private class WorkerEntry {
		Worker worker;
		long fetchTime;

		public WorkerEntry(Worker worker, long fetchTime) {
			super();
			this.worker = worker;
			this.fetchTime = fetchTime;
		}

	}

	public void shutdown() {
		synchronized (this) {
			notifyAll();
		}
		synchronized (cache) {
			cache.notifyAll();
		}

	}

	public void join() {

	}

	public String getSortedWorkerListString() {
		StringBuilder sb = new StringBuilder("WORKERS:\n");
		ArrayList<String> names = new ArrayList(cache.keySet());
		Collections.sort(names);
		for (String name : names) {
			WorkerEntry entry = cache.get(name);
			sb.append("name: '").append(name).append("', LP conns: ")
					.append(entry == null ? "?" : entry.worker.getCurrentLongpollConnections())
					.append("', bad LP conns: ").append(entry == null ? "?" : entry.worker.getBadLongpollConnections())
					.append("\n");
		}
		sb.append("BAD_WORKERS:\n");
		names = new ArrayList(badWorkers.keySet());
		Collections.sort(names);
		for (String name : names) {
			sb.append(name).append("\n");
		}
		return sb.toString();
	}
	
	public void preloadBadWorkers(File homeDir) {
		File[] files = homeDir.listFiles(new FilenameFilter() {
			
			@Override
			public boolean accept(File dir, String name) {
				return name.equals("badworkers-1-" + Conf.get().getHttpJsonRpcPort() + ".tmp") 
						|| name.equals("badworkers-2-" + Conf.get().getHttpJsonRpcPort() + ".tmp");
			}
		});
		ArrayList<String> names = null;
		//sort files by date.
		Arrays.sort(files, new Comparator<File>() {
			@Override
			public int compare(File o1, File o2) {
				return (int) (o1.lastModified() - o2.lastModified());
			}
		});
		for (File file: files) {
			try {
				names = loadBadWorkerNames(new FileInputStream(file));
			} catch (FileNotFoundException e) {
				e.printStackTrace();
			}
			if (names != null)
				break;
		}
		if (names != null) {
			for (String name: names) {
				registerBadWorker(name, false);
			}
		}

	}
	
	public void preloadWorkers(File homeDir) {
		File[] files = homeDir.listFiles(new FilenameFilter() {
			
			@Override
			public boolean accept(File dir, String name) {
				return name.equals("workers-1-" + Conf.get().getHttpJsonRpcPort() + ".tmp") 
						|| name.equals("workers-2-" + Conf.get().getHttpJsonRpcPort() + ".tmp");
			}
		});
		ArrayList<Integer> ids = null;
		//sort files by date.
		Arrays.sort(files, new Comparator<File>() {
			@Override
			public int compare(File o1, File o2) {
				return (int) (o1.lastModified() - o2.lastModified());
			}
		});
		for (File file: files) {
			try {
				ids = loadWorkerIds(new FileInputStream(file));
			} catch (FileNotFoundException e) {
				e.printStackTrace();
			}
			if (ids != null)
				break;
		}
		if (ids != null) {
			try {
				List<Worker> workers = workerDBEngine.fetchWorkersById(ids);
				for (Worker worker: workers) {
					cacheWorker(worker);
				}
			} catch (SQLException e) {
				Res.logException(e);
			}
		}
	}
	
	private ArrayList<Integer> loadWorkerIds(FileInputStream fin) {
		try {
			ObjectInputStream in = new ObjectInputStream(fin);
			WorkerIdSerializationContainer container = (WorkerIdSerializationContainer) in.readObject();
			return container.ids;
		} catch (Exception e) {
			try {
				fin.close();
			} catch (Exception e1) {}
			Res.logException(e);
		}
		return null;		
	}
	
	private ArrayList<String> loadBadWorkerNames(FileInputStream fin) {
		try {
			ObjectInputStream in = new ObjectInputStream(fin);
			BadWorkerNameSerializationContainer container = (BadWorkerNameSerializationContainer) in.readObject();
			return container.names;
		} catch (Exception e) {
			try {
				fin.close();
			} catch (Exception e1) {}
			Res.logException(e);
		}
		return null;		
	}
	
	private class WorkerIdSerializationContainer implements Serializable {
		ArrayList<Integer> ids = new ArrayList();
	}
	
	private class BadWorkerNameSerializationContainer implements Serializable {
		ArrayList<String> names = new ArrayList();
	}

}
