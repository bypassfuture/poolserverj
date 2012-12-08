package com.shadworld.poolserver.entity;

import java.io.Serializable;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashSet;

public class Worker implements Serializable {

//	// LP spam debugging fields
//	public long blocknum;
//	public long blockChangeTime;
//
//	public int lpRequestsSinceBlockChange;
//	public int normalRequestsSinceChange;
//	public int totalRequestsSinceChange;
//	public int qosPriorityChecksSinceChange;
//
//	public void registerQoSPriorityCheck(long currentBlock) {
//		checkNewBlock(currentBlock);
//		qosPriorityChecksSinceChange++;
//	}
//
//	public void registerWorkerRequest(long currentBlock, boolean isAuthed, boolean isLongpollServlet, boolean isGet) {
//		checkNewBlock(currentBlock);
//		if (isLongpollServlet)
//			lpRequestsSinceBlockChange++;
//		else
//			normalRequestsSinceChange++;
//		totalRequestsSinceChange++;
//	}
//
//	private void checkNewBlock(long currentBlock) {
//		synchronized (this) {
//			if (currentBlock > blocknum) {
//				blocknum = currentBlock;
//				blockChangeTime = System.currentTimeMillis();
//				lpRequestsSinceBlockChange = 0;
//				normalRequestsSinceChange = 0;
//				totalRequestsSinceChange = 0;
//				qosPriorityChecksSinceChange = 0;
//			}
//		}
//
//	}
//
//	// end LP spam debug fields

	private Integer id;
	private Integer userId;
	private String username;
	private String password;

	private int currentLongpollConnections = 0;
	private int badLongpollConnections = 0;

	private int maxLongpollConnections = -1;

	boolean badWorker = false;

	int deliveredWorkThisBlock = 0;
	int deliveredWorkLastBlock = 0;

	int workThisBlock = 0;
	int workLastBlock = 0;

	int badLoginCount = 0;
	boolean loggedIn = false;

	private HashSet<String> allowedHosts = new HashSet();
	private boolean hostCheckingEnabled = false;

	public boolean isAllowedHost(String host) {
		if (hostCheckingEnabled) {
			if (allowedHosts.contains(host))
				return true;
			for (String h : allowedHosts) {
				try {
					InetAddress[] addresses = InetAddress.getAllByName(host);
					boolean found = false;
					for (InetAddress address : addresses) {
						String ip = address.getHostAddress();
						found = found || host.equals(ip);
						allowedHosts.add(ip);
					}
					if (found)
						return true;
				} catch (UnknownHostException e) {
				}
			}
		}
		return true;
	}

	public void registerValidWork() {
		synchronized (this) {
			workThisBlock++;
		}
	}

	public void registerWorkDelivered(int numWorks) {
		synchronized (this) {
			deliveredWorkThisBlock += numWorks;
		}
	}

	/**
	 * efficiency of worker for current block and last block. workDone /
	 * workDelivered. It is possible for this to be greater than 1 if the worker
	 * finds more than one solution for the block.
	 * 
	 * @return
	 */
	public float efficiency() {
		float delivered = workSentToWorker();
		if (delivered == 0)
			return Float.NaN;
		return workDoneByWorker() / delivered;
	}

	/**
	 * efficiency of worker for the last block. workDone / workDelivered. It is
	 * possible for this to be greater than 1 if the worker finds more than one
	 * solution for the block.
	 * 
	 * @return
	 */
	public float efficiencyLastBlock() {
		if (deliveredWorkLastBlock == 0)
			return Float.NaN;
		return workLastBlock / deliveredWorkLastBlock;
	}

	public void notifyBlockChange() {
		synchronized (this) {
			workLastBlock = workThisBlock;
			workThisBlock = 0;
			deliveredWorkLastBlock = deliveredWorkThisBlock;
			deliveredWorkThisBlock = 0;
		}
	}

	/**
	 * since worker was retrieved from database. This is used by QosFilter for
	 * prioritizing work requests to workers who've submitted proof of work.
	 * 
	 * @param username
	 * @return
	 */
	public int workDoneByWorker() {
		synchronized (this) {
			return workThisBlock + workLastBlock;
		}
	}

	/**
	 * since worker was retrieved from database. This is used for calculating
	 * efficiency
	 * 
	 * @param username
	 * @return
	 */
	public int workSentToWorker() {
		synchronized (this) {
			return deliveredWorkThisBlock + deliveredWorkLastBlock;
		}
	}

	/**
	 * @return true if worker is not a valid worker in the database
	 */
	public boolean isBadWorker() {
		return badWorker;
	}

	/**
	 * @param badWorker
	 *            the badWorker to set
	 */
	public void setBadWorker(boolean badWorker) {
		this.badWorker = badWorker;
	}

	/**
	 * @return the workThisBlock
	 */
	public int getWorkThisBlock() {
		return workThisBlock;
	}

	/**
	 * @param workThisBlock
	 *            the workThisBlock to set
	 */
	public void setWorkThisBlock(int workThisBlock) {
		this.workThisBlock = workThisBlock;
	}

	/**
	 * @return the workLastBlock
	 */
	public int getWorkLastBlock() {
		return workLastBlock;
	}

	/**
	 * @param workLastBlock
	 *            the workLastBlock to set
	 */
	public void setWorkLastBlock(int workLastBlock) {
		this.workLastBlock = workLastBlock;
	}

	/**
	 * @return the loggedIn
	 */
	public boolean isLoggedIn() {
		return loggedIn;
	}

	/**
	 * @param loggedIn
	 *            the loggedIn to set
	 */
	public void setLoggedIn(boolean loggedIn) {
		this.loggedIn = loggedIn;
		if (loggedIn)
			badLoginCount = 0;
		else
			badLoginCount++;
	}

	/**
	 * @return the badLoginCount
	 */
	public int getBadLoginCount() {
		return badLoginCount;
	}

	/**
	 * @return the id
	 */
	public Integer getId() {
		return id;
	}

	/**
	 * @param id
	 *            the id to set
	 */
	public void setId(Integer id) {
		this.id = id;
	}

	/**
	 * @return the username
	 */
	public String getUsername() {
		return username;
	}

	/**
	 * @param username
	 *            the username to set
	 */
	public void setUsername(String username) {
		this.username = username;
	}

	/**
	 * @return the password
	 */
	public String getPassword() {
		return password;
	}

	/**
	 * @param password
	 *            the password to set
	 */
	public void setPassword(String password) {
		this.password = password;
	}

	// /**
	// * @param allowedHosts the allowedHosts to set
	// */
	// public void setAllowedHosts(HashSet<String> allowedHosts) {
	// this.allowedHosts = allowedHosts;
	// }

	/**
	 * DONT ALTER THIS DIRECTLY. Use isAllowedHost(String host)
	 * 
	 * @return the allowedHosts
	 */
	public HashSet<String> getAllowedHosts() {
		return allowedHosts;
	}

	/**
	 * @param hostCheckingEnabled
	 *            the hostCheckingEnabled to set
	 */
	public void setHostCheckingEnabled(boolean hostCheckingEnabled) {
		this.hostCheckingEnabled = hostCheckingEnabled;
	}

	/**
	 * @return the userId
	 */
	public Integer getUserId() {
		return userId;
	}

	/**
	 * @param userId
	 *            the userId to set
	 */
	public void setUserId(Integer userId) {
		this.userId = userId;
	}

	/**
	 * @return the maxLongpollConnections
	 */
	public int getMaxLongpollConnections() {
		return maxLongpollConnections;
	}

	/**
	 * @param maxLongpollConnections
	 *            the maxLongpollConnections to set
	 */
	public void setMaxLongpollConnections(int maxLongpollConnections) {
		this.maxLongpollConnections = maxLongpollConnections;
	}

	public synchronized boolean allowNewLongpoll() {
		currentLongpollConnections++;
		if (maxLongpollConnections < 1) {
			return true;
		}
		if (currentLongpollConnections <= maxLongpollConnections) {
			return true;
		}
		currentLongpollConnections--;
		badLongpollConnections++;
		return false;
	}

	public synchronized void removeLongpoll() {
		if (currentLongpollConnections > 0)
			currentLongpollConnections--;
	}

	public synchronized void removeBadLongpoll() {
		if (badLongpollConnections > 0)
			badLongpollConnections--;
	}

	public synchronized void resetLongpollCount() {
		currentLongpollConnections = 0;
		badLongpollConnections = 0;
	}

	/**
	 * @return the currentLongpollConnections
	 */
	public int getCurrentLongpollConnections() {
		return currentLongpollConnections;
	}

	/**
	 * @return the badLongpollConnections
	 */
	public int getBadLongpollConnections() {
		return badLongpollConnections;
	}

}
