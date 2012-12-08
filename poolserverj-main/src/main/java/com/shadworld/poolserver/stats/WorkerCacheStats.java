package com.shadworld.poolserver.stats;

import java.util.HashMap;

import com.shadworld.util.MovingAverage;

public class WorkerCacheStats {

	int cacheMisses = 0;
	int cacheHits = 0;
	MovingAverage cacheHitRate = new MovingAverage(500);
	HashMap<String, Integer> notFoundWorkers = new HashMap();
	HashMap<String, String> workerDatabaseFails = new HashMap();
	
	
	
	
}
