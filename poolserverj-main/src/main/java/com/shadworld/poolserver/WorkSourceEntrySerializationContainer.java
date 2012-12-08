package com.shadworld.poolserver;

import gnu.trove.map.hash.THashMap;

import java.io.Serializable;
import java.util.Date;
import java.util.HashMap;

import com.shadworld.poolserver.entity.UniquePortionString;

class WorkSourceEntrySerializationContainer implements Serializable {
	
	static final long serialVersionUID = 1L;
	
	public Date dumpTime = new Date();
	
	public long thisBlockNum;
	public long lastBlockNum;
	public THashMap<UniquePortionString, WorkSourceEntry> sentBlocksCache = new THashMap();
	public THashMap<UniquePortionString, WorkSourceEntry> lastBlocksentBlocksCache;
	public THashMap<UniquePortionString, WorkSourceEntry> submittedWork = new THashMap();
	public THashMap<UniquePortionString, WorkSourceEntry> lastBlockSubmittedWork;
	
	

}