package com.shadworld.poolserver;

import java.util.LinkedHashMap;

public class WorkMap<K> extends LinkedHashMap<K, WorkSourceEntry> {

	final long maxEntryAge;
	final int maxCapacity;
	
	public WorkMap(long maxEntryAge) {
		super();
		this.maxEntryAge = maxEntryAge;
		this.maxCapacity = -1;
		if (maxEntryAge < 1) 
			throw new IllegalArgumentException("maxEntryAge must be positive, for non expiring workmap use a HashMap");
	}
	
	public WorkMap(long maxEntryAge, int maxCapacity) {
		super();
		this.maxEntryAge = maxEntryAge;
		this.maxCapacity = maxCapacity;
		if (maxEntryAge < 1) 
			throw new IllegalArgumentException("maxEntryAge must be positive, for non expiring workmap use a HashMap");
	}

	/* (non-Javadoc)
	 * @see java.util.LinkedHashMap#removeEldestEntry(java.util.Map.Entry)
	 */
	@Override
	protected boolean removeEldestEntry(java.util.Map.Entry<K, WorkSourceEntry> eldest) {
		if (maxCapacity > 0 && size() > maxCapacity)
			return true;
		return System.currentTimeMillis() > eldest.getValue().createTime + maxEntryAge;

	}
	
}
